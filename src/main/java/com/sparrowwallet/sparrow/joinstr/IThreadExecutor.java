package com.sparrowwallet.sparrow.joinstr;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public interface IThreadExecutor {
    ExecutorService threadPool = Executors.newFixedThreadPool(10, r -> {
        Thread t = Executors.defaultThreadFactory().newThread(r);
        t.setDaemon(true);
        return t;
    });;
    private void shutdownThreads() {};
}
