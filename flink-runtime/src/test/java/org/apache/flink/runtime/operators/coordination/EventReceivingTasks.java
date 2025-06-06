/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.operators.coordination;

import org.apache.flink.runtime.concurrent.ComponentMainThreadExecutor;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.operators.coordination.util.IncompleteFuturesTracker;
import org.apache.flink.runtime.testutils.DirectScheduledExecutorService;
import org.apache.flink.util.SerializedValue;
import org.apache.flink.util.concurrent.FutureUtils;
import org.apache.flink.util.concurrent.ScheduledExecutor;
import org.apache.flink.util.concurrent.ScheduledExecutorServiceAdapter;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.apache.flink.runtime.executiongraph.ExecutionGraphTestUtils.createExecutionAttemptId;

/**
 * A test implementation of the BiFunction interface used as the underlying event sender in the
 * {@link OperatorCoordinatorHolder}.
 */
public class EventReceivingTasks implements SubtaskAccess.SubtaskAccessFactory {

    public static EventReceivingTasks createForNotYetRunningTasks() {
        return new EventReceivingTasks(false, CompletableFuture.completedFuture(Acknowledge.get()));
    }

    public static EventReceivingTasks createForRunningTasks() {
        return new EventReceivingTasks(true, CompletableFuture.completedFuture(Acknowledge.get()));
    }

    public static EventReceivingTasks createForRunningTasksFailingRpcs(Throwable rpcException) {
        return new EventReceivingTasks(true, FutureUtils.completedExceptionally(rpcException));
    }

    public static EventReceivingTasks createForRunningTasksWithRpcResult(
            CompletableFuture<Acknowledge> result) {
        return new EventReceivingTasks(true, result);
    }

    // ------------------------------------------------------------------------

    final ArrayList<EventWithSubtask> events = new ArrayList<>();

    private final CompletableFuture<Acknowledge> eventSendingResult;

    private final boolean createdTasksAreRunning;

    private EventReceivingTasks(
            final boolean createdTasksAreRunning,
            final CompletableFuture<Acknowledge> eventSendingResult) {
        this.createdTasksAreRunning = createdTasksAreRunning;
        this.eventSendingResult = eventSendingResult;
    }

    // ------------------------------------------------------------------------
    //  Access to sent events
    // ------------------------------------------------------------------------

    public int getNumberOfSentEvents() {
        return events.size();
    }

    public List<EventWithSubtask> getAllSentEvents() {
        return events;
    }

    public List<OperatorEvent> getSentEventsForSubtask(int subtaskIndex) {

        // Create a new array list to avoid concurrent modification during processing the events
        return new ArrayList<>(events)
                .stream()
                        .filter((evt) -> evt.subtask == subtaskIndex)
                        .map((evt) -> evt.event)
                        .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------------
    //  Controlling the life cycle of the target tasks
    // ------------------------------------------------------------------------

    @Override
    public Collection<SubtaskAccess> getAccessesForSubtask(int subtaskIndex) {
        return Collections.singleton(getAccessForAttempt(subtaskIndex, 0));
    }

    @Override
    public SubtaskAccess getAccessForAttempt(int subtaskIndex, int attemptNumber) {
        return new TestSubtaskAccess(subtaskIndex, attemptNumber, createdTasksAreRunning);
    }

    public OperatorCoordinator.SubtaskGateway createGatewayForSubtask(
            int subtaskIndex, int attemptNumber) {
        final SubtaskAccess sta = getAccessForAttempt(subtaskIndex, attemptNumber);
        return new SubtaskGatewayImpl(
                sta,
                new NoMainThreadCheckComponentMainThreadExecutor(),
                new IncompleteFuturesTracker());
    }

    Callable<CompletableFuture<Acknowledge>> createSendAction(OperatorEvent event, int subtask) {
        return () -> {
            events.add(new EventWithSubtask(event, subtask));
            return eventSendingResult;
        };
    }

    // ------------------------------------------------------------------------

    /** A combination of an {@link OperatorEvent} and the target subtask it is sent to. */
    static final class EventWithSubtask {

        public final OperatorEvent event;
        public final int subtask;

        public EventWithSubtask(OperatorEvent event, int subtask) {
            this.event = event;
            this.subtask = subtask;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final EventWithSubtask that = (EventWithSubtask) o;
            return subtask == that.subtask && event.equals(that.event);
        }

        @Override
        public int hashCode() {
            return Objects.hash(event, subtask);
        }

        @Override
        public String toString() {
            return event + " => subtask " + subtask;
        }
    }

    // ------------------------------------------------------------------------

    final class TestSubtaskAccess implements SubtaskAccess {

        private final ExecutionAttemptID executionAttemptId;
        private final CompletableFuture<?> running;
        private final int subtaskIndex;
        private final List<Throwable> taskFailoverReasons = new ArrayList<>();

        private TestSubtaskAccess(int subtaskIndex, int attemptNumber, boolean isRunning) {
            this.subtaskIndex = subtaskIndex;
            this.executionAttemptId =
                    createExecutionAttemptId(new JobVertexID(), subtaskIndex, attemptNumber);
            this.running = new CompletableFuture<>();
            if (isRunning) {
                switchToRunning();
            }
        }

        @Override
        public Callable<CompletableFuture<Acknowledge>> createEventSendAction(
                SerializedValue<OperatorEvent> event) {

            final OperatorEvent deserializedEvent;
            try {
                deserializedEvent = event.deserializeValue(getClass().getClassLoader());
            } catch (IOException | ClassNotFoundException e) {
                throw new AssertionError(e);
            }

            return createSendAction(deserializedEvent, subtaskIndex);
        }

        @Override
        public int getSubtaskIndex() {
            return subtaskIndex;
        }

        @Override
        public ExecutionAttemptID currentAttempt() {
            return executionAttemptId;
        }

        @Override
        public String subtaskName() {
            return "test_task-" + subtaskIndex + " #: " + executionAttemptId;
        }

        @Override
        public CompletableFuture<?> hasSwitchedToRunning() {
            return running;
        }

        @Override
        public boolean isStillRunning() {
            return true;
        }

        void switchToRunning() {
            running.complete(null);
        }

        @Override
        public void triggerTaskFailover(Throwable cause) {
            taskFailoverReasons.add(cause);
        }

        public List<Throwable> getTaskFailoverReasons() {
            return taskFailoverReasons;
        }
    }

    /**
     * An implementation of {@link ComponentMainThreadExecutor} that executes Runnables with a
     * wrapped {@link ScheduledExecutor} and disables {@link #assertRunningInMainThread()} checks.
     */
    private static class NoMainThreadCheckComponentMainThreadExecutor
            implements ComponentMainThreadExecutor {
        private final ScheduledExecutor scheduledExecutor;

        private NoMainThreadCheckComponentMainThreadExecutor() {
            this.scheduledExecutor =
                    new ScheduledExecutorServiceAdapter(new DirectScheduledExecutorService());
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return scheduledExecutor.schedule(command, delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return scheduledExecutor.schedule(callable, delay, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            return scheduledExecutor.scheduleAtFixedRate(command, initialDelay, period, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            return scheduledExecutor.scheduleAtFixedRate(command, initialDelay, delay, unit);
        }

        @Override
        public void assertRunningInMainThread() {}

        @Override
        public void execute(@Nonnull Runnable command) {
            scheduledExecutor.execute(command);
        }
    }
}
