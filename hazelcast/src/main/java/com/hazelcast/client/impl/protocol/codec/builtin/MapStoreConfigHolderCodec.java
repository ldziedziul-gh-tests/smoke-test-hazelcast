/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.client.impl.protocol.codec.builtin;

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.task.dynamicconfig.MapStoreConfigHolder;
import com.hazelcast.nio.Bits;
import com.hazelcast.nio.serialization.Data;

import java.util.ListIterator;
import java.util.Map;

import static com.hazelcast.client.impl.protocol.ClientMessage.BEGIN_FRAME;
import static com.hazelcast.client.impl.protocol.ClientMessage.END_FRAME;
import static com.hazelcast.client.impl.protocol.codec.builtin.CodecUtil.fastForwardToEndFrame;

public class MapStoreConfigHolderCodec {
    private static final int ENABLED_OFFSET = 0;
    private static final int WRITE_COALESCING_OFFSET = ENABLED_OFFSET + Bits.BOOLEAN_SIZE_IN_BYTES;
    private static final int WRITE_DELAY_SECONDS_OFFSET = WRITE_COALESCING_OFFSET + Bits.BOOLEAN_SIZE_IN_BYTES;
    private static final int WRITE_BATCH_SIZE_OFFSET = WRITE_DELAY_SECONDS_OFFSET + Bits.INT_SIZE_IN_BYTES;
    private static final int INITIAL_FRAME_SIZE = WRITE_BATCH_SIZE_OFFSET + Bits.INT_SIZE_IN_BYTES;

    public static void encode(ClientMessage clientMessage, MapStoreConfigHolder configHolder) {
        clientMessage.addFrame(BEGIN_FRAME);

        ClientMessage.Frame initialFrame = new ClientMessage.Frame(new byte[INITIAL_FRAME_SIZE]);
        FixedSizeTypesCodec.encodeBoolean(initialFrame.content, ENABLED_OFFSET, configHolder.isEnabled());
        FixedSizeTypesCodec.encodeBoolean(initialFrame.content, WRITE_BATCH_SIZE_OFFSET, configHolder.isWriteCoalescing());
        FixedSizeTypesCodec.encodeInt(initialFrame.content, WRITE_DELAY_SECONDS_OFFSET, configHolder.getWriteDelaySeconds());
        FixedSizeTypesCodec.encodeInt(initialFrame.content, WRITE_BATCH_SIZE_OFFSET, configHolder.getWriteBatchSize());
        clientMessage.addFrame(initialFrame);

        CodecUtil.encodeNullable(clientMessage, configHolder.getClassName(), StringCodec::encode);
        CodecUtil.encodeNullable(clientMessage, configHolder.getFactoryClassName(), StringCodec::encode);
        CodecUtil.encodeNullable(clientMessage, configHolder.getImplementation(), DataCodec::encode);
        CodecUtil.encodeNullable(clientMessage, configHolder.getFactoryImplementation(), DataCodec::encode);
        MapCodec.encodeNullable(clientMessage, configHolder.getProperties().entrySet(), StringCodec::encode, StringCodec::encode);
        StringCodec.encode(clientMessage, configHolder.getInitialLoadMode());

        clientMessage.addFrame(END_FRAME);
    }

    public static MapStoreConfigHolder decode(ListIterator<ClientMessage.Frame> iterator) {
        iterator.next(); // begin frame

        ClientMessage.Frame initialFrame = iterator.next();
        boolean enabled = FixedSizeTypesCodec.decodeBoolean(initialFrame.content, ENABLED_OFFSET);
        boolean writeCoalescing = FixedSizeTypesCodec.decodeBoolean(initialFrame.content, WRITE_COALESCING_OFFSET);
        int writeDelaySeconds = FixedSizeTypesCodec.decodeInt(initialFrame.content, WRITE_DELAY_SECONDS_OFFSET);
        int writeBatchSize = FixedSizeTypesCodec.decodeInt(initialFrame.content, WRITE_BATCH_SIZE_OFFSET);

        String className = CodecUtil.decodeNullable(iterator, StringCodec::decode);
        String factoryClassName = CodecUtil.decodeNullable(iterator, StringCodec::decode);
        Data implementation = CodecUtil.decodeNullable(iterator, DataCodec::decode);
        Data factoryImplementation = CodecUtil.decodeNullable(iterator, DataCodec::decode);
        Map<String, String> properties = MapCodec.decodeToNullableMap(iterator, StringCodec::decode, StringCodec::decode);
        String initialLoadMode = StringCodec.decode(iterator);

        fastForwardToEndFrame(iterator);

        MapStoreConfigHolder configHolder = new MapStoreConfigHolder();
        configHolder.setEnabled(enabled);
        configHolder.setWriteCoalescing(writeCoalescing);
        configHolder.setWriteDelaySeconds(writeDelaySeconds);
        configHolder.setWriteBatchSize(writeBatchSize);
        configHolder.setClassName(className);
        configHolder.setFactoryClassName(factoryClassName);
        configHolder.setImplementation(implementation);
        configHolder.setFactoryImplementation(factoryImplementation);
        configHolder.setProperties(properties);
        configHolder.setInitialLoadMode(initialLoadMode);
        return configHolder;
    }
}
