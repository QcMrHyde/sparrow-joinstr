package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A pool's private key reads its encrypted channel and answers its join requests. Once the pool
 * is finished the key has no further use, and keeping it only widens the window in which it can
 * leak from the pool store.
 */
public class PoolKeyLifetimeTest {

    private JoinstrPool pool(String timeout, String status) {
        JoinstrPool pool = new JoinstrPool("wss://nos.lol", "pk", "0.001", "3", timeout,
                "aabbccddeeff", status);
        return pool;
    }

    private String future() {
        return String.valueOf(Instant.now().getEpochSecond() + 3600);
    }

    private String past() {
        return String.valueOf(Instant.now().getEpochSecond() - 60);
    }

    @Test
    public void aRunningPoolKeepsItsKey() {
        JoinstrPool running = pool(future(), "Input registration");

        assertFalse(running.isFinished());
        assertEquals(0, JoinstrPool.purgeFinishedKeys(List.of(running)));
        assertEquals("aabbccddeeff", running.getPrivateKey());
    }

    @Test
    public void aCompletedPoolLosesItsKey() {
        JoinstrPool complete = pool(future(), "Complete");

        assertTrue(complete.isFinished());
        assertEquals(1, JoinstrPool.purgeFinishedKeys(List.of(complete)));
        assertEquals("", complete.getPrivateKey());
    }

    @Test
    public void anExpiredPoolLosesItsKey() {
        JoinstrPool expired = pool(past(), "Waiting for credentials");

        assertTrue(expired.isFinished());
        assertEquals(1, JoinstrPool.purgeFinishedKeys(List.of(expired)));
        assertEquals("", expired.getPrivateKey());
    }

    /** Disarming a pool because its timeout could not be read would break a live coinjoin. */
    @Test
    public void aPoolWithAnUnreadableTimeoutKeepsItsKey() {
        JoinstrPool odd = pool("not a timestamp", "Input registration");

        assertFalse(odd.isFinished());
        assertEquals(0, JoinstrPool.purgeFinishedKeys(List.of(odd)));
        assertEquals("aabbccddeeff", odd.getPrivateKey());
    }

    @Test
    public void purgingIsIdempotentAndCountsOnlyWhatItDropped() {
        JoinstrPool complete = pool(future(), "Complete");

        assertEquals(1, JoinstrPool.purgeFinishedKeys(List.of(complete)));
        assertEquals(0, JoinstrPool.purgeFinishedKeys(List.of(complete)));
    }

    @Test
    public void onlyTheFinishedPoolsInAStoreArePurged() {
        JoinstrPool running = pool(future(), "Input registration");
        JoinstrPool complete = pool(future(), "Complete");
        JoinstrPool expired = pool(past(), "");

        assertEquals(2, JoinstrPool.purgeFinishedKeys(List.of(running, complete, expired)));
        assertEquals("aabbccddeeff", running.getPrivateKey());
        assertEquals("", complete.getPrivateKey());
        assertEquals("", expired.getPrivateKey());
    }

    /** Dropping the key must not remove the pool or its history from the list. */
    @Test
    public void purgingKeepsThePoolItself() {
        JoinstrPool complete = pool(future(), "Complete");

        JoinstrPool.purgeFinishedKeys(List.of(complete));

        assertEquals("pk", complete.getPubkey());
        assertEquals("0.001", complete.getDenomination());
        assertEquals("Complete", complete.getStatus());
    }
}
