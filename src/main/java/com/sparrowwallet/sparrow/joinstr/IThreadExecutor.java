package com.sparrowwallet.sparrow.joinstr;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Interface for classes that manage threads.
 */
public interface IThreadExecutor {

    /**
     * Returns the current ExecutorService instance.
     */
    ExecutorService getExecutorService();

    /**
     * Sets a new ExecutorService instance.
     */
    void setExecutorService(ExecutorService executorService);

    /**
     * Creates a new ExecutorService instance.
     * Override this to customize the executor type.
     */
    default ExecutorService createExecutorService() {
        return Executors.newCachedThreadPool();
    }

    /**
     * Checks if the ExecutorService needs to be reset and resets it if necessary.
     * Returns the valid ExecutorService.
     */
    default ExecutorService ensureExecutorServiceRunning() {
        synchronized (this) {
            ExecutorService current = getExecutorService();
            if (current == null || current.isShutdown() || current.isTerminated()) {
                ExecutorService newExecutor = createExecutorService();
                setExecutorService(newExecutor);
                current = newExecutor;
            }
            return current;
        }
    }

    /**
     * Submits a task to the ExecutorService, resetting it if necessary.
     */
    default void submitTask(Runnable task) {
        ExecutorService current = ensureExecutorServiceRunning();
        current.submit(task);
    }

    /**
     * Shuts down the ExecutorService gracefully.
     */
    default void shutdownThreads() {
        synchronized (this) {
            ExecutorService current = getExecutorService();
            if (current != null && !current.isShutdown()) {
                current.shutdown();
                try {
                    if (!current.awaitTermination(3, TimeUnit.SECONDS)) {
                        current.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    current.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}