package com.hazelcast.tpc.engine;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hazelcast.test.HazelcastTestSupport.assertEqualsEventually;
import static com.hazelcast.test.HazelcastTestSupport.assertOpenEventually;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;

public abstract class Eventloop_Unsafe_Test {

    private Eventloop eventloop;

    public abstract Eventloop create();

    @Before
    public void before() {
        eventloop = create();
        eventloop.start();
    }

    @After
    public void after() {
        eventloop.shutdown();
    }


    @Test
    public void test_sleep() {
        AtomicInteger executedCount = new AtomicInteger();
        long startMs = System.currentTimeMillis();
        eventloop.execute(() -> eventloop.unsafe.sleep(1, SECONDS)
                .then((o, ex) -> executedCount.incrementAndGet()));


        assertEqualsEventually(1, executedCount);
        long duration = System.currentTimeMillis() - startMs;
        System.out.println("duration:" + duration + " ms");
    }

    @Test
    public void test_loop() {
        AtomicInteger executedCount = new AtomicInteger();
        int iterations = 10;
        CountDownLatch completed = new CountDownLatch(1);

        eventloop.execute(() -> eventloop.unsafe.loop(eventloop -> {
            executedCount.incrementAndGet();
            return executedCount.get() < iterations;
        }).then((o,ex) -> completed.countDown()));


        assertOpenEventually(completed);
        assertEquals(executedCount.get(), iterations);
    }
}