/*
 * Copyright (c) 2008-2022, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.tpc.engine.iouring;

import com.hazelcast.internal.util.collection.Int2ObjectHashMap;
import com.hazelcast.tpc.engine.AsyncFile;
import com.hazelcast.tpc.engine.Promise;
import com.hazelcast.tpc.engine.iouring.IOUringEventloop.IOUringUnsafe;
import com.hazelcast.tpc.util.CircularQueue;
import com.hazelcast.tpc.util.SlabAllocator;

import io.netty.incubator.channel.uring.IOUringSubmissionQueue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static com.hazelcast.internal.util.Preconditions.checkNotNull;
import static com.hazelcast.internal.util.Preconditions.checkPositive;


/**
 * Responsible for scheduling I/O requests.
 *
 * If the maxConcurrency isn't reached, the ioRequests will be submitted to the ringbuffer. Otherwise
 * the can be buffer until maxPending is reached. After that they will get rejected.
 *
 * What isn't great is that the maxConcurrency is global. This is fine if you just have a single
 * storage device; but if you have 2 or more than this can lead to saturation or under utilization.
 */
public class IORequestScheduler {

    private final SlabAllocator<IoRequest> ioRequestAllocator;

    private IOUringEventloop eventloop;
    private IOUringSubmissionQueue sq;
    private final Int2ObjectHashMap<AsyncFileIoHandler> fileRequests = new Int2ObjectHashMap<>();
    private IOUringUnsafe unsafe;
    private final List<StorageDevice> devs = new ArrayList<>();

    public IORequestScheduler(int maxPending) {
        this.ioRequestAllocator = new SlabAllocator<>(maxPending, IoRequest::new);
    }

    public void init(IOUringEventloop eventloop) {
        this.eventloop = eventloop;
        this.unsafe = (IOUringUnsafe) eventloop.unsafe();
        this.sq = eventloop.sq;
    }

    public StorageDevice findStorageDevice(String path) {
        for (StorageDevice dev : devs) {
            if (path.startsWith(dev.path)) {
                return dev;
            }
        }
        return null;
    }

    public void registerStorageDevice(String path, int maxConcurrent, int maxPending) {
        File file = new File(path);
        if(!file.exists()){
            throw new RuntimeException("A storage device ["+path+"] doesn't exit");
        }

        if(!file.isDirectory()){
            throw new RuntimeException("A storage device ["+path+"] is not a directory");
        }

        if (findStorageDevice(path) != null) {
            throw new RuntimeException("A storage device with path [" + path + "] already exists");
        }

        checkPositive("maxConcurrent", maxConcurrent);

        StorageDevice dev = new StorageDevice(path, maxConcurrent, maxPending);
        devs.add(dev);
    }

    // todo: we can do actual registration on the rb.
    public void registerAsyncFile(IOUringAsyncFile file) {
        checkNotNull(file);

        file.dev = findStorageDevice(file.path());
        if(file.dev == null){
            throw new UncheckedIOException(new IOException("Could not find storage device for ["+file.path()+"]"));
        }else{
            System.out.println("found: file.dev:"+file.dev.path);
        }

        System.out.println("register " + file.fd());
        AsyncFileIoHandler ioRequests = new AsyncFileIoHandler(file);
        file.requests = ioRequests;


        fileRequests.put(file.fd(), ioRequests);
        eventloop.completionListeners.put(file.fd(), ioRequests);
    }

    private void issueNext(StorageDevice dev) {
        if (dev.concurrent < dev.maxConcurrent) {
            IoRequest req = dev.pending.poll();
            if (req != null) {
                submitToSq(req);
            }
        }
    }

    public Promise issue(IOUringAsyncFile file,
                         byte op,
                         int flags,
                         int rwFlags,
                         long bufferAddress,
                         int length,
                         long offset) {
        IoRequest req = ioRequestAllocator.allocate();
        req.file = file;
        req.op = op;
        req.flags = flags;
        req.rwFlags = rwFlags;
        req.bufferAddress = bufferAddress;
        req.length = length;
        req.offset = offset;

        Promise promise = unsafe.newPromise();
        req.promise = promise;

        StorageDevice dev = file.dev;
        if (dev.concurrent < dev.maxConcurrent) {
            submitToSq(req);
        } else if (!dev.pending.offer(req)) {
            promise.completeExceptionally(new IOException("Overload"));
        }

        return promise;
    }

    private void submitToSq(IoRequest req) {
        short reqId = (short) req.file.requests.ioSlots.insert(req);

        req.file.dev.concurrent++;

        // todo: we are not doing anything with the returned value.
        boolean x = sq.enqueueSqe(
                req.op,
                req.flags,
                req.rwFlags,
                req.file.fd,
                req.bufferAddress,
                req.length,
                req.offset,
                reqId);
    }

    /**
     * The IORequests for a single file.
     *
     * This approach is needed because the way netty has exposed the ringbuffer. Otherwise
     * it would be better to have IORequests for a single storage device.
     */
    class AsyncFileIoHandler implements CompletionListener {
        private final Slots<IoRequest> ioSlots;
        private final AsyncFile file;

        private AsyncFileIoHandler(IOUringAsyncFile file) {
            this.file = file;
            this.ioSlots = new Slots<>(file.dev.maxConcurrent);
        }

        @Override
        public void handle(int fd, int res, int flags, byte op, short data) {
            IoRequest req = ioSlots.remove(data);
            if (req == null) {
                return;
            }
            req.file.dev.concurrent--;
            if (res < 0) {
                req.promise.completeExceptionally(
                        new IOException(file.path() + " res=" + -res + " op=" + op + " for info see: " +
                                "https://www.thegeekstuff.com/2010/10/linux-error-codes/"));
            } else {
                req.promise.complete(true);
            }

            issueNext(req.file.dev);
            req.clear();
            ioRequestAllocator.free(req);
        }
    }

    static class StorageDevice {
        private final int maxConcurrent;
        private final String path;
        private int concurrent;
        private final CircularQueue<IoRequest> pending;

        StorageDevice(String path, int maxConcurrent, int maxPending) {
            this.path = path;
            this.maxConcurrent = maxConcurrent;
            this.pending = new CircularQueue<>(maxPending);
        }
    }

    private static class IoRequest {
        private IOUringAsyncFile file;
        private long offset;
        private int length;
        private byte op;
        private int flags;
        private int rwFlags;
        private long bufferAddress;
        private Promise promise;

        private void clear() {
            flags = 0;
            rwFlags = 0;
            length = 0;
            promise = null;
            file = null;
        }
    }
}
