package com.platform.portal.common;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded daemon thread pools for blocking I/O fan-out (Java 17 has no virtual threads). */
public final class Threads {

    private Threads() {
    }

    /** Up to {@code maxThreads} named daemon threads; extra tasks queue, idle threads exit after 60s. */
    public static ExecutorService pool(String name, int maxThreads) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(maxThreads, maxThreads, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), daemonFactory(name));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** Starts a single named daemon thread. */
    public static Thread start(String name, Runnable task) {
        Thread thread = daemonFactory(name).newThread(task);
        thread.start();
        return thread;
    }

    private static ThreadFactory daemonFactory(String name) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
