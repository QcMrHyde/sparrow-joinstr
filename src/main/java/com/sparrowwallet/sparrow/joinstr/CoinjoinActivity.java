package com.sparrowwallet.sparrow.joinstr;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks whether a coinjoin is in flight.
 *
 * Pool discovery and a running coinjoin share one nostr client, and discovery takes a fresh Tor
 * circuit by disconnecting it. Doing that while a coinjoin is waiting for peers tears down the
 * subscription it depends on, so discovery stands aside until the coinjoin is finished.
 */
public final class CoinjoinActivity {

    private static final AtomicInteger active = new AtomicInteger();

    private CoinjoinActivity() {
    }

    public static void started() {
        active.incrementAndGet();
    }

    public static void finished() {
        active.updateAndGet(count -> count > 0 ? count - 1 : 0);
    }

    public static boolean isActive() {
        return active.get() > 0;
    }

    static void resetForTesting() {
        active.set(0);
    }
}
