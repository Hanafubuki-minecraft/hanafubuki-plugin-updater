package com.hanafubuki.updater.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Thread pools that cost nothing while idle. */
public final class Threads {

    private Threads() {
    }

    /**
     * A single daemon thread that runs tasks in order and exits after {@code idleSeconds}
     * without work; it is started again for the next task.
     */
    public static ExecutorService lazySingle(String name, int idleSeconds) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, idleSeconds, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
