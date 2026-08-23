package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pool discovery and a running coinjoin share one nostr client, and discovery takes a fresh Tor
 * circuit by disconnecting it. The refresh timer keeps firing every 30 seconds once the Other
 * Pools tab has been opened, and is only cancelled when the whole joinstr window closes, so
 * without this a coinjoin's subscription was torn down repeatedly while it waited for peers.
 */
public class CoinjoinActivityTest {

    @BeforeEach
    @AfterEach
    public void reset() {
        CoinjoinActivity.resetForTesting();
    }

    private CoinjoinHandler handler() {
        JoinstrPool pool = new JoinstrPool("wss://nos.lol", "pk", "0.001", "3", "1750000000");
        return new CoinjoinHandler(null, pool, null, null, status -> {
        });
    }

    @Test
    public void nothingIsHeldWhenIdle() {
        assertFalse(CoinjoinActivity.isActive());
    }

    @Test
    public void aRunningCoinjoinHoldsOffDiscovery() {
        CoinjoinActivity.started();

        assertTrue(CoinjoinActivity.isActive());
    }

    @Test
    public void discoveryResumesOnceTheCoinjoinFinishes() {
        CoinjoinActivity.started();
        CoinjoinActivity.finished();

        assertFalse(CoinjoinActivity.isActive());
    }

    /** A stuck counter would disable discovery for the rest of the session. */
    @Test
    public void anExtraFinishCannotDriveTheCountNegative() {
        CoinjoinActivity.finished();
        CoinjoinActivity.finished();
        CoinjoinActivity.started();

        assertTrue(CoinjoinActivity.isActive());

        CoinjoinActivity.finished();
        assertFalse(CoinjoinActivity.isActive());
    }

    @Test
    public void aSecondCoinjoinKeepsTheHoldUntilBothFinish() {
        CoinjoinActivity.started();
        CoinjoinActivity.started();
        CoinjoinActivity.finished();

        assertTrue(CoinjoinActivity.isActive());

        CoinjoinActivity.finished();
        assertFalse(CoinjoinActivity.isActive());
    }

    // --- the handler's own bookkeeping ---

    /**
     * With no relay reachable the subscription cannot start, and the coinjoin ends there. What
     * matters is that it does not leave discovery held off for the rest of the session.
     */
    @Test
    public void aCoinjoinThatCannotSubscribeDoesNotHoldDiscovery() {
        CoinjoinHandler handler = handler();
        assertFalse(CoinjoinActivity.isActive());

        handler.startOutputPhase("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq");

        assertFalse(CoinjoinActivity.isActive(),
                "a coinjoin that failed to start left discovery blocked");
    }

    @Test
    public void tearingDownReleasesTheHold() {
        CoinjoinActivity.started();
        assertTrue(CoinjoinActivity.isActive());

        CoinjoinActivity.finished();

        assertFalse(CoinjoinActivity.isActive());
    }

    /**
     * A coinjoin that completes and is then hit by its own pool timeout tears down twice. The
     * second must not release a hold taken by an unrelated coinjoin.
     */
    @Test
    public void tearingDownTwiceReleasesTheHoldOnce() {
        CoinjoinHandler handler = handler();
        // stand in for a coinjoin that got as far as holding, without needing a relay
        handler.holdDiscoveryForTesting();
        CoinjoinActivity.started();

        handler.stopListening();
        handler.stopListening();
        handler.stopListening();

        assertTrue(CoinjoinActivity.isActive(), "another coinjoin's hold was released");
        CoinjoinActivity.finished();
        assertFalse(CoinjoinActivity.isActive());
    }

    /** A handler that never started must not release anything on teardown. */
    @Test
    public void tearingDownWithoutStartingReleasesNothing() {
        CoinjoinActivity.started();

        handler().stopListening();

        assertTrue(CoinjoinActivity.isActive());
    }

    @Test
    public void anInvalidOwnAddressDoesNotLeaveDiscoveryHeld() {
        handler().startOutputPhase("not-an-address");

        assertFalse(CoinjoinActivity.isActive(),
                "a rejected address left discovery blocked for the session");
    }
}
