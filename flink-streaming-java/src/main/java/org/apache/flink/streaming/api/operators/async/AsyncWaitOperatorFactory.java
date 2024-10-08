/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.operators.async;

import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.functions.async.AsyncFunction;
import org.apache.flink.streaming.api.functions.async.AsyncRetryStrategy;
import org.apache.flink.streaming.api.operators.AbstractStreamOperatorFactory;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperatorFactory;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorParameters;
import org.apache.flink.streaming.api.operators.legacy.YieldingOperatorFactory;

import static org.apache.flink.streaming.util.retryable.AsyncRetryStrategies.NO_RETRY_STRATEGY;

/**
 * The factory of {@link AsyncWaitOperator}.
 *
 * @param <OUT> The output type of the operator
 */
public class AsyncWaitOperatorFactory<IN, OUT> extends AbstractStreamOperatorFactory<OUT>
        implements OneInputStreamOperatorFactory<IN, OUT>, YieldingOperatorFactory<OUT> {

    private final AsyncFunction<IN, OUT> asyncFunction;
    private final long timeout;
    private final int capacity;
    private final AsyncDataStream.OutputMode outputMode;
    private final AsyncRetryStrategy<OUT> asyncRetryStrategy;

    public AsyncWaitOperatorFactory(
            AsyncFunction<IN, OUT> asyncFunction,
            long timeout,
            int capacity,
            AsyncDataStream.OutputMode outputMode) {
        this(asyncFunction, timeout, capacity, outputMode, NO_RETRY_STRATEGY);
    }

    public AsyncWaitOperatorFactory(
            AsyncFunction<IN, OUT> asyncFunction,
            long timeout,
            int capacity,
            AsyncDataStream.OutputMode outputMode,
            AsyncRetryStrategy<OUT> asyncRetryStrategy) {
        this.asyncFunction = asyncFunction;
        this.timeout = timeout;
        this.capacity = capacity;
        this.outputMode = outputMode;
        this.chainingStrategy = ChainingStrategy.ALWAYS;
        this.asyncRetryStrategy = asyncRetryStrategy;
    }

    @Override
    public <T extends StreamOperator<OUT>> T createStreamOperator(
            StreamOperatorParameters<OUT> parameters) {
        AsyncWaitOperator asyncWaitOperator =
                new AsyncWaitOperator(
                        parameters,
                        asyncFunction,
                        timeout,
                        capacity,
                        outputMode,
                        asyncRetryStrategy,
                        processingTimeService,
                        getMailboxExecutor());
        return (T) asyncWaitOperator;
    }

    @Override
    public Class<? extends StreamOperator> getStreamOperatorClass(ClassLoader classLoader) {
        return AsyncWaitOperator.class;
    }
}
