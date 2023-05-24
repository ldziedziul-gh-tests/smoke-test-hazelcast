/*
 * Copyright (c) 2008-2023, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.internal.tpcengine.util;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;

public final class BufferUtil {

    private static final Field ADDRESS;
    private static final Field CAPACITY;
    private static final long ADDRESS_OFFSET;
    private static final Unsafe UNSAFE = UnsafeLocator.UNSAFE;
    private static final int PAGE_SIZE = OS.pageSize();
    private static final long CAPACITY_OFFSET;

    static {
        try {
            ADDRESS = Buffer.class.getDeclaredField("address");
            CAPACITY = Buffer.class.getDeclaredField("capacity");
            ADDRESS_OFFSET = UNSAFE.objectFieldOffset(ADDRESS);
            CAPACITY_OFFSET = UNSAFE.objectFieldOffset(CAPACITY);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BufferUtil() {
    }

    public static ByteBuffer allocateDirect(int capacity, int alignment) {
        if (alignment < 0) {
            throw new IllegalArgumentException("alignment can't be smaller than 1");
        } else if (alignment == 1) {
            return ByteBuffer.allocateDirect(capacity);
        } else {
            // TODO: The problem is when the ByteBuffer is deallocated because the 'address'
            //  is used for deallocation and not 'base'
            long base = UNSAFE.allocateMemory(capacity + alignment);
            long address = toAlignedAddress(base, alignment);
            return newDirectByteBuffer(address, capacity);
        }
    }

    public static ByteBuffer newDirectByteBuffer(long address, int capacity) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(0);
        UNSAFE.putLong(buffer, ADDRESS_OFFSET, address);
        UNSAFE.putInt(buffer, CAPACITY_OFFSET, capacity);
        buffer.limit(capacity);
        return buffer;
    }

    public static long addressOf(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Only direct bytebuffers allowed");
        }
        return UNSAFE.getLong(buffer, ADDRESS_OFFSET);
    }

    public static long toPageAlignedAddress(long base) {
        if (base % PAGE_SIZE == 0) {
            return base;
        } else {
            return base - base % PAGE_SIZE + PAGE_SIZE;
        }
    }

    public static long toAlignedAddress(long base, int alignment) {
        if (base % alignment == 0) {
            return base;
        } else {
            return base - base % alignment + alignment;
        }
    }


    /**
     * Creates a debug String for te given ByteBuffer. Useful when debugging IO.
     * <p>
     * Do not remove even if this method isn't used.
     *
     * @param name       name of the ByteBuffer.
     * @param byteBuffer the ByteBuffer
     * @return the debug String
     */
    public static String toDebugString(String name, ByteBuffer byteBuffer) {
        return name + "(pos:" + byteBuffer.position() + " lim:" + byteBuffer.limit()
                + " remain:" + byteBuffer.remaining() + " cap:" + byteBuffer.capacity() + ")";
    }

    /**
     * Compacts or clears the buffer depending if bytes are remaining in the byte-buffer.
     *
     * @param bb the ByteBuffer
     */
    public static void compactOrClear(ByteBuffer bb) {
        if (bb.hasRemaining()) {
            bb.compact();
        } else {
            bb.clear();
        }
    }

     public static void put(ByteBuffer dst, ByteBuffer src) {
        if (src.remaining() <= dst.remaining()) {
            // there is enough space in the dst buffer to copy the src
            dst.put(src);
        } else {
            // there is not enough space in the dst buffer, so we need to
            // copy as much as we can.
            int srcOldLimit = src.limit();
            src.limit(src.position() + dst.remaining());
            dst.put(src);
            src.limit(srcOldLimit);
        }
    }

}
