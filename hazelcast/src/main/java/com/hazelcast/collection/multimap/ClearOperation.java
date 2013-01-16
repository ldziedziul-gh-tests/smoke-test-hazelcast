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

package com.hazelcast.collection.multimap;

import com.hazelcast.collection.CollectionContainer;
import com.hazelcast.collection.CollectionProxyType;
import com.hazelcast.core.EntryEventType;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.spi.BackupAwareOperation;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.PartitionLevelOperation;

import java.util.Collection;
import java.util.Map;

/**
 * @ali 1/9/13
 */
public class ClearOperation extends MultiMapOperation implements BackupAwareOperation, PartitionLevelOperation {


    transient Map<Data, Collection> entries;

    public ClearOperation() {
    }

    public ClearOperation(String name, CollectionProxyType proxyType) {
        super(name, proxyType);
    }

    public void beforeRun() throws Exception {
        if (hasListener()) {
            CollectionContainer container = getOrCreateContainer();
            entries = container.entrySet();
        }
    }

    public void run() throws Exception {
        CollectionContainer container = getOrCreateContainer();
        container.clear();
        response = true;
    }

    public void afterRun() throws Exception {
        if (!entries.isEmpty()) {
            for (Map.Entry<Data, Collection> entry : entries.entrySet()) {
                Data key = entry.getKey();
                Collection coll = entry.getValue();
                for (Object obj : coll) {
                    publishEvent(EntryEventType.REMOVED, key, obj);
                }
            }
            entries.clear();
        }
        entries = null;
    }

    public boolean shouldBackup() {
        return Boolean.TRUE.equals(entries.isEmpty());
    }

    public Operation getBackupOperation() {
        return new ClearBackupOperation(name, proxyType);
    }

}
