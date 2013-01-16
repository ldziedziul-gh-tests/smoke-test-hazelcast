/*
 * Copyright (c) 2008-2012, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.collection.multimap;

import com.hazelcast.collection.CollectionContainer;
import com.hazelcast.collection.CollectionProxyType;
import com.hazelcast.collection.WaitKey;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.WaitNotifyKey;
import com.hazelcast.spi.WaitSupport;

import java.io.IOException;

/**
 * @ali 1/16/13
 */
public class LockOperation extends CollectionBackupAwareOperation implements WaitSupport {

    public static final long DEFAULT_LOCK_TTL = 5 * 60 * 1000;

    long ttl = DEFAULT_LOCK_TTL;

    long timeout;

    public LockOperation() {
    }

    public LockOperation(String name, CollectionProxyType proxyType, Data dataKey, int threadId, long timeout) {
        super(name, proxyType, dataKey, threadId);
        this.timeout = timeout;
    }

    public LockOperation(String name, CollectionProxyType proxyType, Data dataKey, int threadId, long ttl, long timeout) {
        this(name, proxyType, dataKey, threadId, timeout);
        this.ttl = ttl;
    }

    public void run() throws Exception {
        CollectionContainer container = getOrCreateContainer();
        response = container.lock(dataKey, getCaller(), threadId, ttl);
    }

    public void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
        out.writeLong(ttl);
        out.writeLong(timeout);
    }

    public void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
        ttl = in.readLong();
        timeout = in.readLong();
    }

    public Operation getBackupOperation() {
        return new LockBackupOperation(name, proxyType, dataKey, ttl, threadId, getCaller());
    }

    public boolean shouldBackup() {
        return Boolean.TRUE.equals(response);
    }

    public WaitNotifyKey getWaitKey() {
        return new WaitKey(name, dataKey, "lock");
    }

    public boolean shouldWait() {
        return timeout != 0 && !getOrCreateContainer().canAcquireLock(dataKey, threadId, getCaller());
    }

    public long getWaitTimeoutMillis() {
        return timeout;
    }

    public void onWaitExpire() {
        getResponseHandler().sendResponse(false);
    }
}
