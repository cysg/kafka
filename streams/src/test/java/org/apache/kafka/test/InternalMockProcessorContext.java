/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.test;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.common.utils.SystemTime;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.processor.Cancellable;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.Punctuator;
import org.apache.kafka.streams.processor.StateRestoreCallback;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.To;
import org.apache.kafka.streams.processor.internals.AbstractProcessorContext;
import org.apache.kafka.streams.processor.internals.ProcessorNode;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.apache.kafka.streams.processor.internals.RecordBatchingStateRestoreCallback;
import org.apache.kafka.streams.processor.internals.RecordCollector;
import org.apache.kafka.streams.processor.internals.StreamTask;
import org.apache.kafka.streams.processor.internals.Task.TaskType;
import org.apache.kafka.streams.processor.internals.ToInternal;
import org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl;
import org.apache.kafka.streams.state.StateSerdes;
import org.apache.kafka.streams.state.internals.ThreadCache;
import org.apache.kafka.streams.state.internals.ThreadCache.DirtyEntryFlushListener;
import org.apache.kafka.streams.state.internals.metrics.RocksDBMetricsRecordingTrigger;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.kafka.streams.processor.internals.StateRestoreCallbackAdapter.adapt;

public class InternalMockProcessorContext
    extends AbstractProcessorContext
    implements RecordCollector.Supplier {

    private final File stateDir;
    private final RecordCollector.Supplier recordCollectorSupplier;
    private final Map<String, StateStore> storeMap = new LinkedHashMap<>();
    private final Map<String, StateRestoreCallback> restoreFuncs = new HashMap<>();
    private final ToInternal toInternal = new ToInternal();

    private TaskType taskType = TaskType.ACTIVE;
    private Serde<?> keySerde;
    private Serde<?> valueSerde;
    private long timestamp = -1L;

    public InternalMockProcessorContext() {
        this(null,
            null,
            null,
            new StreamsMetricsImpl(new Metrics(), "mock", StreamsConfig.METRICS_LATEST),
            new StreamsConfig(StreamsTestUtils.getStreamsConfig()),
            null,
            null
        );
    }

    public InternalMockProcessorContext(final File stateDir,
                                        final StreamsConfig config) {
        this(
            stateDir,
            null,
            null,
            new StreamsMetricsImpl(new Metrics(), "mock", config.getString(StreamsConfig.BUILT_IN_METRICS_VERSION_CONFIG)),
            config,
            null,
            null
        );
    }

    public InternalMockProcessorContext(final StreamsMetricsImpl streamsMetrics) {
        this(
            null,
            null,
            null,
            streamsMetrics,
            new StreamsConfig(StreamsTestUtils.getStreamsConfig()),
            null,
            null
        );
    }

    public InternalMockProcessorContext(final File stateDir,
                                        final StreamsConfig config,
                                        final RecordCollector collector) {
        this(
            stateDir,
            null,
            null,
            new StreamsMetricsImpl(new Metrics(), "mock", config.getString(StreamsConfig.BUILT_IN_METRICS_VERSION_CONFIG)),
            config,
            () -> collector,
            null
        );
    }

    public InternalMockProcessorContext(final File stateDir,
                                        final Serde<?> keySerde,
                                        final Serde<?> valueSerde,
                                        final StreamsConfig config) {
        this(
            stateDir,
            keySerde,
            valueSerde,
            new StreamsMetricsImpl(new Metrics(), "mock", StreamsConfig.METRICS_LATEST),
            config,
            null,
            null
        );
    }

    public InternalMockProcessorContext(final StateSerdes<?, ?> serdes,
                                        final RecordCollector collector) {
        this(null, serdes.keySerde(), serdes.valueSerde(), collector, null);
    }

    public InternalMockProcessorContext(final StateSerdes<?, ?> serdes,
                                        final RecordCollector collector,
                                        final Metrics metrics) {
        this(
            null,
            serdes.keySerde(),
            serdes.valueSerde(),
            new StreamsMetricsImpl(metrics, "mock", StreamsConfig.METRICS_LATEST),
            new StreamsConfig(StreamsTestUtils.getStreamsConfig()),
            () -> collector,
            null
        );
    }

    public InternalMockProcessorContext(final File stateDir,
                                        final Serde<?> keySerde,
                                        final Serde<?> valueSerde,
                                        final RecordCollector collector,
                                        final ThreadCache cache) {
        this(
            stateDir,
            keySerde,
            valueSerde,
            new StreamsMetricsImpl(new Metrics(), "mock", StreamsConfig.METRICS_LATEST),
            new StreamsConfig(StreamsTestUtils.getStreamsConfig()),
            () -> collector,
            cache
        );
    }

    public InternalMockProcessorContext(final File stateDir,
                                        final Serde<?> keySerde,
                                        final Serde<?> valueSerde,
                                        final StreamsMetricsImpl metrics,
                                        final StreamsConfig config,
                                        final RecordCollector.Supplier collectorSupplier,
                                        final ThreadCache cache) {
        super(
            new TaskId(0, 0),
            config,
            metrics,
            null,
            cache
        );
        super.setCurrentNode(new ProcessorNode<>("TESTING_NODE"));
        this.stateDir = stateDir;
        this.keySerde = keySerde;
        this.valueSerde = valueSerde;
        this.recordCollectorSupplier = collectorSupplier;
        this.metrics().setRocksDBMetricsRecordingTrigger(new RocksDBMetricsRecordingTrigger(new SystemTime()));
    }

    @Override
    public RecordCollector recordCollector() {
        final RecordCollector recordCollector = recordCollectorSupplier.recordCollector();

        if (recordCollector == null) {
            throw new UnsupportedOperationException("No RecordCollector specified");
        }
        return recordCollector;
    }

    public void setKeySerde(final Serde<?> keySerde) {
        this.keySerde = keySerde;
    }

    public void setValueSerde(final Serde<?> valueSerde) {
        this.valueSerde = valueSerde;
    }

    @Override
    public Serde<?> keySerde() {
        return keySerde;
    }

    @Override
    public Serde<?> valueSerde() {
        return valueSerde;
    }

    // state mgr will be overridden by the state dir and store maps
    @Override
    public void initialize() {}

    @Override
    public File stateDir() {
        if (stateDir == null) {
            throw new UnsupportedOperationException("State directory not specified");
        }
        return stateDir;
    }

    @Override
    public void register(final StateStore store,
                         final StateRestoreCallback func) {
        storeMap.put(store.name(), store);
        restoreFuncs.put(store.name(), func);
    }

    @Override
    public StateStore getStateStore(final String name) {
        return storeMap.get(name);
    }

    @Override
    @Deprecated
    public Cancellable schedule(final long interval, final PunctuationType type, final Punctuator callback) {
        throw new UnsupportedOperationException("schedule() not supported.");
    }

    @Override
    public Cancellable schedule(final Duration interval,
                                final PunctuationType type,
                                final Punctuator callback) throws IllegalArgumentException {
        throw new UnsupportedOperationException("schedule() not supported.");
    }

    @Override
    public void commit() {}

    @Override
    public void forward(final Object key, final Object value) {
        forward(key, value, To.all());
    }

    @Override
    @Deprecated
    public void forward(final Object key, final Object value, final int childIndex) {
        forward(key, value, To.child((currentNode().children()).get(childIndex).name()));
    }

    @Override
    @Deprecated
    public void forward(final Object key, final Object value, final String childName) {
        forward(key, value, To.child(childName));
    }

    @SuppressWarnings("unchecked")
    @Override
    public void forward(final Object key, final Object value, final To to) {
        toInternal.update(to);
        if (toInternal.hasTimestamp()) {
            setTime(toInternal.timestamp());
        }
        final ProcessorNode<?, ?> thisNode = currentNode;
        try {
            for (final ProcessorNode<?, ?> childNode : thisNode.children()) {
                if (toInternal.child() == null || toInternal.child().equals(childNode.name())) {
                    currentNode = childNode;
                    ((ProcessorNode<Object, Object>) childNode).process(key, value);
                    toInternal.update(to); // need to reset because MockProcessorContext is shared over multiple
                                           // Processors and toInternal might have been modified
                }
            }
        } finally {
            currentNode = thisNode;
        }
    }

    // allow only setting time but not other fields in for record context,
    // and also not throwing exceptions if record context is not available.
    public void setTime(final long timestamp) {
        if (recordContext != null) {
            recordContext = new ProcessorRecordContext(
                timestamp,
                recordContext.offset(),
                recordContext.partition(),
                recordContext.topic(),
                recordContext.headers()
            );
        }
        this.timestamp = timestamp;
    }

    @Override
    public long timestamp() {
        if (recordContext == null) {
            return timestamp;
        }
        return recordContext.timestamp();
    }

    @Override
    public String topic() {
        if (recordContext == null) {
            return null;
        }
        return recordContext.topic();
    }

    @Override
    public int partition() {
        if (recordContext == null) {
            return -1;
        }
        return recordContext.partition();
    }

    @Override
    public long offset() {
        if (recordContext == null) {
            return -1L;
        }
        return recordContext.offset();
    }

    @Override
    public Headers headers() {
        if (recordContext == null) {
            return new RecordHeaders();
        }
        return recordContext.headers();
    }

    @Override
    public TaskType taskType() {
        return taskType;
    }

    @Override
    public void logChange(final String storeName,
                          final Bytes key,
                          final byte[] value,
                          final long timestamp) {
        recordCollector().send(
            storeName + "-changelog",
            key,
            value,
            null,
            taskId().partition,
            timestamp,
            BYTES_KEY_SERIALIZER,
            BYTEARRAY_VALUE_SERIALIZER);
    }

    @Override
    public void transitionToActive(final StreamTask streamTask, final RecordCollector recordCollector, final ThreadCache newCache) {
        taskType = TaskType.ACTIVE;
    }

    @Override
    public void transitionToStandby(final ThreadCache newCache) {
        taskType = TaskType.STANDBY;
    }

    @Override
    public void registerCacheFlushListener(final String namespace, final DirtyEntryFlushListener listener) {
        cache().addDirtyEntryFlushListener(namespace, listener);
    }

    public void restore(final String storeName, final Iterable<KeyValue<byte[], byte[]>> changeLog) {
        final RecordBatchingStateRestoreCallback restoreCallback = adapt(restoreFuncs.get(storeName));

        final List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
        for (final KeyValue<byte[], byte[]> keyValue : changeLog) {
            records.add(new ConsumerRecord<>("", 0, 0L, keyValue.key, keyValue.value));
        }
        restoreCallback.restoreBatch(records);
    }
}
