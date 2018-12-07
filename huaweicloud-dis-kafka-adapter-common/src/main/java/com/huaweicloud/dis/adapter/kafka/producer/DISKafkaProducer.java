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

package com.huaweicloud.dis.adapter.kafka.producer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.huaweicloud.dis.adapter.kafka.model.ProduceCallback;
import com.huaweicloud.dis.adapter.kafka.model.ProducerRecord;
import com.huaweicloud.dis.producer.internals.ProduceRequestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.huaweicloud.dis.DISConfig;
import com.huaweicloud.dis.core.handler.AsyncHandler;
import com.huaweicloud.dis.core.util.StringUtils;
import com.huaweicloud.dis.iface.data.request.PutRecordsRequest;
import com.huaweicloud.dis.iface.data.request.PutRecordsRequestEntry;
import com.huaweicloud.dis.iface.data.response.PutRecordsResult;
import com.huaweicloud.dis.iface.data.response.PutRecordsResultEntry;
import com.huaweicloud.dis.iface.stream.request.DescribeStreamRequest;
import com.huaweicloud.dis.iface.stream.response.DescribeStreamResult;
import com.huaweicloud.dis.producer.DISProducer;
import com.huaweicloud.dis.adapter.kafka.AbstractAdapter;

public class DISKafkaProducer extends AbstractAdapter implements IDISProducer
{
    
    private static final Logger log = LoggerFactory.getLogger(DISKafkaProducer.class);
    
    protected DISProducer producer;

    
    public DISKafkaProducer(DISConfig disConfig)
    {
        super(disConfig);
    }

    
    
    @Override
    public Future<PutRecordsResult> send(ProducerRecord record)
    {
        return send(record, null);
    }
    
    @Override
    public Future<PutRecordsResult> send(ProducerRecord record, final ProduceCallback callback)
    {
        
        String streamName = record.stream();
        PutRecordsRequest request = new PutRecordsRequest();
        request.setStreamName(streamName);
        
        List<PutRecordsRequestEntry> recordEntrys = new ArrayList<PutRecordsRequestEntry>();
        
        PutRecordsRequestEntry entry = new PutRecordsRequestEntry();

		if(record.timestamp()!=null)
        {
            entry.setTimestamp(record.timestamp());
        }
                
        recordEntrys.add(entry);
        
        request.setRecords(recordEntrys);
        
        Future<PutRecordsResult> putResultFuture = null;
        try
        {
            putResultFuture = producer.putRecordsAsync(request, new AsyncHandler<PutRecordsResult>()
                {
                    @Override
                    public void onSuccess(PutRecordsResult result)
                    {
                        if (callback != null)
                        {
                            callback.onCompletion(result, null);
                        }
                    }
                    
                    @Override
                    public void onError(Exception exception)
                    {
                        if (callback != null)
                        {
                            callback.onCompletion(null, exception);
                        }
                    }
                });
        }
        catch (InterruptedException e1)
        {
            throw new RuntimeException(e1);
        }
        
        return putResultFuture;
        
    }
    
    @Override
    public void flush()
    {
        producer.flush();
    }

    @Override
    public DescribeStreamResult describeStream(String stream) {
        DescribeStreamRequest describeStreamRequest = new DescribeStreamRequest();
        describeStreamRequest.setStreamName(stream);
        describeStreamRequest.setLimitPartitions(1);
        DescribeStreamResult describeStreamResult = disClient.describeStream(describeStreamRequest);
        return describeStreamResult;
    }

    @Override
    public void close()
    {
        this.close(10000L, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close(long timeout, TimeUnit unit)
    {
        this.producer.close();
    }

}
