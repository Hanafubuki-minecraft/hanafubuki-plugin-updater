package com.hanafubuki.updater.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ThreadsTest {

    @Test
    void runsTasksInOrderOnOneDaemonThreadThatExitsWhenIdle() throws Exception {
        ExecutorService pool = Threads.lazySingle("test-lazy", 1);
        List<Integer> order = new CopyOnWriteArrayList<>();
        List<String> names = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 20; i++) {
            int n = i;
            pool.execute(() -> {
                order.add(n);
                names.add(Thread.currentThread().getName() + ":" + Thread.currentThread().isDaemon());
            });
        }
        long deadline = System.currentTimeMillis() + 5_000;
        while (((ThreadPoolExecutor) pool).getPoolSize() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(java.util.stream.IntStream.range(0, 20).boxed().toList(), order);
        assertTrue(names.stream().allMatch(n -> n.equals("test-lazy:true")), names.toString());
        assertEquals(0, ((ThreadPoolExecutor) pool).getPoolSize(), "idle thread has exited");

        // Works again after the thread exited.
        pool.submit(() -> order.add(99)).get(5, TimeUnit.SECONDS);
        assertEquals(99, order.get(order.size() - 1));
        pool.shutdownNow();
    }
}
