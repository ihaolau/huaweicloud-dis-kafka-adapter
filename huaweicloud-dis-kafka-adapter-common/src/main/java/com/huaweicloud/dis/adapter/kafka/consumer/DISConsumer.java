/*
 * Copyright 2002-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huaweicloud.dis.adapter.kafka.consumer;

import com.huaweicloud.dis.DISConfig;
import com.huaweicloud.dis.adapter.kafka.AbstractAdapter;
import com.huaweicloud.dis.adapter.kafka.Utils;
import com.huaweicloud.dis.adapter.kafka.model.OffsetAndMetadata;
import com.huaweicloud.dis.adapter.kafka.model.OffsetResetStrategy;
import com.huaweicloud.dis.adapter.kafka.model.StreamPartition;
import com.huaweicloud.dis.iface.data.response.Record;
import com.huaweicloud.dis.iface.stream.request.DescribeStreamRequest;
import com.huaweicloud.dis.iface.stream.request.ListStreamsRequest;
import com.huaweicloud.dis.iface.stream.response.DescribeStreamResult;
import com.huaweicloud.dis.iface.stream.response.ListStreamsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;


public class DISConsumer extends AbstractAdapter implements IDISConsumer
{
    private static final Logger log = LoggerFactory.getLogger(DISConsumer.class);
    private static final long NO_CURRENT_THREAD = -1L;
    private Coordinator coordinator;
    private SubscriptionState subscriptions;
    private boolean closed = false;
    private final AtomicLong currentThread = new AtomicLong(NO_CURRENT_THREAD);
    private final AtomicInteger refcount = new AtomicInteger(0);
    
    private String clientId;
    private String groupId;
    private Fetcher fetcher;
    ConcurrentHashMap<StreamPartition,PartitionCursor> nextIterators;

    public DISConsumer(Map configs){this(newDisConfig(configs));}
    public DISConsumer(DISConfig disConfig) {
        super(disConfig);
        this.clientId = disConfig.get("client.id","consumer-"+ UUID.randomUUID());
        this.groupId = disConfig.get("group.id","");
        OffsetResetStrategy offsetResetStrategy = OffsetResetStrategy.valueOf(disConfig.get("auto.offset.reset","LATEST").toUpperCase());
        this.subscriptions = new SubscriptionState(offsetResetStrategy);
        this.nextIterators = new ConcurrentHashMap<>();
        boolean autoCommitEnabled = disConfig.getBoolean("enable.auto.commit",true);
        long autoCommitIntervalMs = Long.valueOf(disConfig.get("auto.commit.interval.ms","5000"));
        this.coordinator = new Coordinator(this.disClient,
                this.clientId,
                this.groupId,
                this.subscriptions,
                autoCommitEnabled,
                autoCommitIntervalMs,
                this.nextIterators,
                disConfig);
        this.fetcher = new Fetcher(disConfig,
                this.subscriptions,
                this.coordinator,
                this.nextIterators);
        log.info("create DISConsumer successfully");
    }

    private static DISConfig newDisConfig(Map map)
    {
        DISConfig disConfig = new DISConfig();
        disConfig.putAll(map);
        return disConfig;
    }
    @Override
    public Set<StreamPartition> assignment() {
        acquire();
        try {
            return Collections.unmodifiableSet(new HashSet<>(this.subscriptions.assignedPartitions()));
        } finally {
            release();
        }
    }

    @Override
    public Set<String> subscription() {
        acquire();
        try {
            return Collections.unmodifiableSet(new HashSet<>(this.subscriptions.subscription()));
        } finally {
            release();
        }
    }

    @Override
    public void subscribe(Collection<String> streams, ConsumerRebalanceListener listener) {
        acquire();
        try {
            if (streams.isEmpty()) {
                this.unsubscribe();
            } else {
                log.debug("Subscribed to stream(s): {}", Utils.join(streams, ", "));
                this.subscriptions.subscribe(streams, listener);
            }
        } finally {
            release();
        }
    }

    @Override
    public void subscribe(Collection<String> streams)
    {
        subscribe(streams, new NoOpConsumerRebalanceListener());
    }

    @Override
    public void assign(Collection<StreamPartition> partitions)
    {
        acquire();
        try {
            log.debug("Subscribed to partition(s): {}", Utils.join(partitions, ", "));
            this.subscriptions.assignFromUser(partitions);
         //   this.subscriptions.commitsRefreshed();
        } finally {
            release();
        }
    }

    @Override
    public void subscribe(Pattern pattern, ConsumerRebalanceListener callback)
    {
        acquire();
        try {
            log.debug("Subscribed to pattern: {}", pattern);
            this.subscriptions.subscribe(pattern, callback);
        } finally {
            release();
        }
        
    }

    @Override
    public void unsubscribe()
    {
        acquire();
        try {
            log.debug("Unsubscribed all streams or patterns and assigned partitions");
            this.subscriptions.unsubscribe();
            coordinator.maybeLeaveGroup();
        } finally {
            release();
        }
    }

    @Override
    public Map<StreamPartition, List<Record>> poll(long timeout)
    {
        acquire();
        try {
            if (timeout < 0)
                throw new IllegalArgumentException("Timeout must not be negative");

            if(subscriptions.partitionsAutoAssigned())
            {
                coordinator.ensureGroupStable();
            }

            fetcher.sendFetchRequests();
            Map<StreamPartition, List<Record>> records =  fetcher.fetchRecords(timeout);
            coordinator.executeDelayedTask();
            return records;
        } finally {
            release();
        }
    }

    @Override
    public void commitSync()
    {
        acquire();
        try {
            commitSync(subscriptions.allConsumed());
        } finally {
            release();
        }
    }

    @Override
    public void commitSync(Map<StreamPartition, OffsetAndMetadata> offsets)
    {
        acquire();
        try {
            coordinator.commitSync(offsets);
        } finally {
            release();
        }
    }

    @Override
    public void commitAsync()
    {
        commitAsync(null);
    }

    @Override
    public void commitAsync(OffsetCommitCallback callback)
    {
        acquire();
        try {
            commitAsync(subscriptions.allConsumed(), callback);
        } finally {
            release();
        }
    }

    @Override
    public void commitAsync(Map<StreamPartition, OffsetAndMetadata> offsets, OffsetCommitCallback callback)
    {
        acquire();
        try {
            log.debug("Committing offsets: {} ", offsets);
            coordinator.commitAsync(new HashMap<>(offsets), callback);
        } finally {
            release();
        }
    }

    @Override
    public void seek(StreamPartition partition, long offset)
    {
        if (offset < 0) {
            throw new IllegalArgumentException("seek offset must not be a negative number");
        }
        acquire();
        try {
            log.debug("Seeking to offset {} for partition {}", offset, partition);
            this.subscriptions.seek(partition, offset);
            nextIterators.remove(partition);
        } finally {
            release();
        }
    }

    @Override
    public void seekToBeginning(Collection<StreamPartition> partitions)
    {
        acquire();
        try {
            Collection<StreamPartition> parts = partitions.size() == 0 ? this.subscriptions.assignedPartitions() : partitions;
            for (StreamPartition tp : parts) {
                log.debug("Seeking to beginning of partition {}", tp);
                subscriptions.needOffsetReset(tp, OffsetResetStrategy.EARLIEST);
            }
        } finally {
            release();
        }
    }

    @Override
    public void seekToEnd(Collection<StreamPartition> partitions)
    {
        acquire();
        try {
            Collection<StreamPartition> parts = partitions.size() == 0 ? this.subscriptions.assignedPartitions() : partitions;
            for (StreamPartition tp : parts) {
                log.debug("Seeking to end of partition {}", tp);
                subscriptions.needOffsetReset(tp, OffsetResetStrategy.LATEST);
            }
        } finally {
            release();
        }
    }

    @Override
    public long position(StreamPartition partition)
    {
        acquire();
        try {
            if (!this.subscriptions.isAssigned(partition))
                throw new IllegalArgumentException("You can only check the position for partitions assigned to this consumer.");
            Set<StreamPartition> needUpdatePositionPartition = new HashSet<>();
            for(StreamPartition part:this.subscriptions.assignedPartitions())
            {
                if(this.subscriptions.position(part) == null)
                {
                    needUpdatePositionPartition.add(part);
                }
            }
            if (!needUpdatePositionPartition.isEmpty()) {
                coordinator.updateFetchPositions(needUpdatePositionPartition);
            }
            return this.subscriptions.position(partition);
        } finally {
            release();
        }
    }

    @Override
    public OffsetAndMetadata committed(StreamPartition partition)
    {
        acquire();
        try {
            OffsetAndMetadata committed;
            if (subscriptions.isAssigned(partition)) {
                committed = this.subscriptions.committed(partition);
                if (committed == null) {
                    coordinator.refreshCommittedOffsetsIfNeeded();
                    committed = this.subscriptions.committed(partition);
                }
            } else {
               Map<StreamPartition, OffsetAndMetadata> offsets = coordinator.fetchCommittedOffsets(Collections.singleton(partition));
               committed = offsets.get(partition);
            }
            return committed;
        } finally {
            release();
        }
    }

    @Override
    public DescribeStreamResult describeStream(String stream)
    {
        acquire();
        try
        {
            DescribeStreamRequest describeStreamRequest = new DescribeStreamRequest();
            describeStreamRequest.setStreamName(stream);
            describeStreamRequest.setLimitPartitions(1);
            DescribeStreamResult describeStreamResult = disClient.describeStream(describeStreamRequest);
            return describeStreamResult;
        }
        finally
        {
            release();
        }
    }

    @Override
    public List<DescribeStreamResult> listStreams()
    {
        acquire();
        List<DescribeStreamResult> results = new ArrayList<>();
        try {
            int limit = 100;
            String startStreamName = "";
            while (true)
            {
                ListStreamsRequest listStreamsRequest = new ListStreamsRequest();
                listStreamsRequest.setLimit(limit);
                listStreamsRequest.setExclusivetartStreamName(startStreamName);
                ListStreamsResult listStreamsResult = disClient.listStreams(listStreamsRequest);
                if(listStreamsResult == null || listStreamsResult.getStreamNames() == null)
                {
                    break;
                }
                List<String > streams = listStreamsResult.getStreamNames();
                for(String stream: streams)
                {
                    DescribeStreamResult describeStreamResult = describeStream(stream);
                    results.add(describeStreamResult);
                }
                if(!listStreamsResult.getHasMoreStreams())
                {
                    break;
                }
                startStreamName = streams.get(streams.size()-1);
            }
        }finally {
            release();
        }
        return results;
    }

    @Override
    public Set<StreamPartition> paused()
    {
        acquire();
        try {
            return Collections.unmodifiableSet(subscriptions.pausedPartitions());
        } finally {
            release();
        }
    }

    @Override
    public void pause(Collection<StreamPartition> partitions)
    {
        acquire();
        try {
            for (StreamPartition partition: partitions) {
                log.debug("Pausing partition {}", partition);
                subscriptions.pause(partition);
                fetcher.pause(partition);
            }
        } finally {
            release();
        }
    }

    @Override
    public void resume(Collection<StreamPartition> partitions)
    {
        acquire();
        try {
            for (StreamPartition partition: partitions) {
                log.debug("Resuming partition {}", partition);
                subscriptions.resume(partition);
            }
        } finally {
            release();
        }
    }

    @Override
    public void close()
    {
        closed = true;
    }


    @Override
    public void wakeup()
    {
        // TODO Auto-generated method stub
        
    }

    private void acquire() {
        ensureNotClosed();
        long threadId = Thread.currentThread().getId();
        if (threadId != currentThread.get() && !currentThread.compareAndSet(NO_CURRENT_THREAD, threadId))
            throw new ConcurrentModificationException("DisConsumer is not safe for multi-threaded access");
        refcount.incrementAndGet();
    }
    private void ensureNotClosed() {
        if (this.closed)
            throw new IllegalStateException("This consumer has already been closed.");
    }

    private void release() {
        if (refcount.decrementAndGet() == 0)
            currentThread.set(NO_CURRENT_THREAD);
    }
}
