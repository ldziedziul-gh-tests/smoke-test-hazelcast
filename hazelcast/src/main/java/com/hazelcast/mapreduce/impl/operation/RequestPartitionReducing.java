/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.mapreduce.impl.operation;

import com.hazelcast.mapreduce.JobPartitionState;
import com.hazelcast.mapreduce.impl.MapReduceDataSerializerHook;
import com.hazelcast.mapreduce.impl.MapReduceService;
import com.hazelcast.mapreduce.impl.task.JobPartitionStateImpl;
import com.hazelcast.mapreduce.impl.task.JobProcessInformationImpl;
import com.hazelcast.mapreduce.impl.task.JobSupervisor;
import com.hazelcast.mapreduce.impl.task.JobTaskConfiguration;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;

import java.io.IOException;
import java.util.Arrays;

public class RequestPartitionReducing
        extends ProcessingOperation {

    protected volatile RequestPartitionResult result;

    private transient int partitionId;

    public RequestPartitionReducing() {
    }

    public RequestPartitionReducing(String name, String jobId, int partitionId) {
        super(name, jobId);
        this.partitionId = partitionId;
    }

    @Override
    public Object getResponse() {
        return result;
    }

    @Override
    public void run() throws Exception {
        MapReduceService mapReduceService = getService();
        JobSupervisor supervisor = mapReduceService.getJobSupervisor(getName(), getJobId());
        if (supervisor == null) {
            result = new RequestPartitionResult(RequestPartitionResult.State.NO_SUPERVISOR, -1);
            return;
        }

        JobPartitionState.State nextState = JobPartitionState.State.REDUCING;
        JobTaskConfiguration configuration = supervisor.getConfiguration();
        if (configuration.getReducerFactory() == null) {
            nextState = JobPartitionState.State.PROCESSED;
        }

        JobProcessInformationImpl processInformation = supervisor.getJobProcessInformation();
        JobPartitionState newPartitionState = new JobPartitionStateImpl(getCallerAddress(), nextState);

        for (; ; ) {
            JobPartitionState[] oldPartitionStates = processInformation.getPartitionStates();
            JobPartitionState[] newPartitionStates = Arrays.copyOf(oldPartitionStates, oldPartitionStates.length);

            // Set new partition processing information
            newPartitionStates[partitionId] = newPartitionState;

            if (processInformation.updatePartitionState(oldPartitionStates, newPartitionStates)) {
                result = new RequestPartitionResult(RequestPartitionResult.State.SUCCESSFUL, partitionId);
                return;
            }
        }
    }

    @Override
    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
        out.writeInt(partitionId);
    }

    @Override
    protected void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
        partitionId = in.readInt();
    }

    @Override
    public int getFactoryId() {
        return MapReduceDataSerializerHook.F_ID;
    }

    @Override
    public int getId() {
        return MapReduceDataSerializerHook.REQUEST_PARTITION_REDUCING;
    }

}
