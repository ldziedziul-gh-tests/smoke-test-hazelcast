package com.hazelcast.spi.impl.reactor.nio;

import com.hazelcast.spi.impl.reactor.frame.Frame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Queue;

public final class IOVector {

    private final static int IOV_MAX = 1024;

    private final ByteBuffer[] array = new ByteBuffer[IOV_MAX];
    private final Frame[] frames = new Frame[IOV_MAX];
    private int size = 0;
    private long pending;

    public boolean isEmpty() {
        return size == 0;
    }

    public void fill(Queue<Frame> queue) {
        int count = IOV_MAX - size;
        for (int k = 0; k < count; k++) {
            Frame frame = queue.poll();
            if (frame == null) {
                break;
            }

            ByteBuffer buffer = frame.byteBuffer();
            array[size] = buffer;
            frames[size] = frame;
            size++;
            pending += buffer.remaining();
        }
    }

    public boolean add(Frame frame) {
        if (size == IOV_MAX) {
            return false;
        } else {
            ByteBuffer buffer = frame.byteBuffer();
            array[size] = buffer;
            frames[size] = frame;
            size++;
            pending += buffer.remaining();
            return true;
        }
    }

    public long write(SocketChannel socketChannel) throws IOException {
        long written;
        if (size == 1) {
            written = socketChannel.write(array[0]);
        } else {
            written = socketChannel.write(array, 0, size);
        }
        compact(written);
        return written;
    }

    void compact(long written) {
        if (written == pending) {
            for (int k = 0; k < size; k++) {
                array[k] = null;
                frames[k].release();
                frames[k] = null;
            }
            size = 0;
            pending = 0;
        } else {
            int toIndex = 0;
            int length = size;
            for (int k = 0; k < length; k++) {
                if (array[k].hasRemaining()) {
                    if (k == 0) {
                        // the first one is not empty, we are done
                        break;
                    } else {
                        array[toIndex] = array[k];
                        array[k] = null;
                        frames[toIndex] = frames[k];
                        frames[k] = null;
                        toIndex++;
                    }
                } else {
                    size--;
                    array[k] = null;
                    frames[k].release();
                    frames[k] = null;
                }
            }
            pending -= written;
        }
    }

    public int size() {
        return size;
    }
}