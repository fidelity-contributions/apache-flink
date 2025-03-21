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

package org.apache.flink.client.program;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.api.common.JobSubmissionResult;
import org.apache.flink.api.common.accumulators.AccumulatorHelper;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.execution.CheckpointType;
import org.apache.flink.core.execution.SavepointFormatType;
import org.apache.flink.runtime.client.JobStatusMessage;
import org.apache.flink.runtime.executiongraph.AccessExecutionGraph;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobmaster.JobResult;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.operators.coordination.CoordinationRequest;
import org.apache.flink.runtime.operators.coordination.CoordinationResponse;
import org.apache.flink.streaming.api.graph.ExecutionPlan;
import org.apache.flink.util.AbstractID;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.SerializedValue;
import org.apache.flink.util.concurrent.FutureUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Client to interact with a {@link MiniCluster}. */
public class MiniClusterClient implements ClusterClient<MiniClusterClient.MiniClusterId> {

    private static final Logger LOG = LoggerFactory.getLogger(MiniClusterClient.class);

    private final MiniCluster miniCluster;
    private final Configuration configuration;

    public MiniClusterClient(
            @Nonnull Configuration configuration, @Nonnull MiniCluster miniCluster) {
        this.configuration = configuration;
        this.miniCluster = miniCluster;
    }

    @Override
    public Configuration getFlinkConfiguration() {
        return new Configuration(configuration);
    }

    @Override
    public CompletableFuture<JobID> submitJob(ExecutionPlan executionPlan) {
        return miniCluster.submitJob(executionPlan).thenApply(JobSubmissionResult::getJobID);
    }

    @Override
    public CompletableFuture<JobResult> requestJobResult(@Nonnull JobID jobId) {
        return miniCluster.requestJobResult(jobId);
    }

    @Override
    public CompletableFuture<Acknowledge> cancel(JobID jobId) {
        return miniCluster.cancelJob(jobId);
    }

    @Override
    public CompletableFuture<String> cancelWithSavepoint(
            JobID jobId, @Nullable String savepointDirectory, SavepointFormatType formatType) {
        return miniCluster.triggerSavepoint(jobId, savepointDirectory, true, formatType);
    }

    @Override
    public CompletableFuture<String> stopWithSavepoint(
            JobID jobId,
            boolean advanceToEndOfEventTime,
            @Nullable String savepointDirectory,
            SavepointFormatType formatType) {
        return miniCluster.stopWithSavepoint(
                jobId, savepointDirectory, advanceToEndOfEventTime, formatType);
    }

    @Override
    public CompletableFuture<String> stopWithDetachedSavepoint(
            JobID jobId,
            boolean advanceToEndOfEventTime,
            @Nullable String savepointDirectory,
            SavepointFormatType formatType) {
        return miniCluster.stopWithDetachedSavepoint(
                jobId, savepointDirectory, advanceToEndOfEventTime, formatType);
    }

    @Override
    public CompletableFuture<String> triggerSavepoint(
            JobID jobId, @Nullable String savepointDirectory, SavepointFormatType formatType) {
        return miniCluster.triggerSavepoint(jobId, savepointDirectory, false, formatType);
    }

    @Override
    public CompletableFuture<Long> triggerCheckpoint(JobID jobId, CheckpointType checkpointType) {
        return miniCluster.triggerCheckpoint(jobId, checkpointType);
    }

    @Override
    public CompletableFuture<String> triggerDetachedSavepoint(
            JobID jobId, @Nullable String savepointDirectory, SavepointFormatType formatType) {
        return miniCluster.triggerDetachedSavepoint(jobId, savepointDirectory, false, formatType);
    }

    @Override
    public CompletableFuture<Acknowledge> disposeSavepoint(String savepointPath) {
        return miniCluster.disposeSavepoint(savepointPath);
    }

    @Override
    public CompletableFuture<Collection<JobStatusMessage>> listJobs() {
        return miniCluster.listJobs();
    }

    @Override
    public CompletableFuture<Map<String, Object>> getAccumulators(JobID jobID, ClassLoader loader) {
        return miniCluster
                .getExecutionGraph(jobID)
                .thenApply(AccessExecutionGraph::getAccumulatorsSerialized)
                .thenApply(
                        accumulators -> {
                            try {
                                return AccumulatorHelper.deserializeAndUnwrapAccumulators(
                                        accumulators, loader);
                            } catch (Exception e) {
                                throw new CompletionException(
                                        "Cannot deserialize and unwrap accumulators properly.", e);
                            }
                        });
    }

    @Override
    public CompletableFuture<JobStatus> getJobStatus(JobID jobId) {
        return miniCluster.getJobStatus(jobId);
    }

    @Override
    public void close() {}

    @Override
    public MiniClusterClient.MiniClusterId getClusterId() {
        return MiniClusterId.INSTANCE;
    }

    @Override
    public void shutDownCluster() {
        try {
            miniCluster.closeAsync().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOG.error("Error while shutting down cluster", e);
        }
    }

    @Override
    public String getWebInterfaceURL() {
        try {
            return miniCluster.getRestAddress().get().toString();
        } catch (InterruptedException | ExecutionException e) {
            ExceptionUtils.checkInterrupted(e);

            LOG.warn("Could not retrieve the web interface URL for the cluster.", e);
            return "Unknown address.";
        }
    }

    @Override
    public CompletableFuture<CoordinationResponse> sendCoordinationRequest(
            JobID jobId, String operatorUid, CoordinationRequest request) {
        try {
            SerializedValue<CoordinationRequest> serializedRequest = new SerializedValue<>(request);
            return miniCluster.deliverCoordinationRequestToCoordinator(
                    jobId, operatorUid, serializedRequest);
        } catch (IOException e) {
            LOG.error("Error while sending coordination request", e);
            return FutureUtils.completedExceptionally(e);
        }
    }

    @Override
    public CompletableFuture<Set<AbstractID>> listCompletedClusterDatasetIds() {
        return miniCluster.listCompletedClusterDatasetIds();
    }

    @Override
    public CompletableFuture<Void> invalidateClusterDataset(AbstractID clusterDatasetId) {
        return miniCluster.invalidateClusterDataset(new IntermediateDataSetID(clusterDatasetId));
    }

    @Override
    public CompletableFuture<Void> reportHeartbeat(JobID jobId, long expiredTimestamp) {
        return miniCluster.reportHeartbeat(jobId, expiredTimestamp);
    }

    /** The type of the Cluster ID for the local {@link MiniCluster}. */
    public enum MiniClusterId {
        INSTANCE
    }
}
