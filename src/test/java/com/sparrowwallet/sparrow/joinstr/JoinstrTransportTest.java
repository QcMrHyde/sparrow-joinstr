package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Publishing a join request, an output address or a signed input from the wallet's real IP links
 * that IP to the coinjoin. Every one of those used to check whether Tor was running and carry on
 * in the clear when it was not, so a slow or failed bootstrap downgraded the join silently.
 */
public class JoinstrTransportTest {

    @AfterEach
    public void restoreRealTor() {
        JoinstrTransport.setTorRunningForTesting(null);
    }

    @Test
    public void nothingIsSentWhenTorIsDown() {
        JoinstrTransport.setTorRunningForTesting(() -> false);

        assertFalse(JoinstrTransport.isReady());
        assertFalse(JoinstrTransport.newCircuit(), "a request would have been sent without tor");
        assertNotNull(JoinstrTransport.unavailableReason());
    }

    @Test
    public void theRefusalSaysWhy() {
        JoinstrTransport.setTorRunningForTesting(() -> false);

        String reason = JoinstrTransport.unavailableReason();
        assertTrue(reason.contains("real IP address"), reason);
        assertTrue(reason.contains("Tor"), reason);
    }

    @Test
    public void aReadyTransportReportsNoReason() {
        JoinstrTransport.setTorRunningForTesting(() -> true);

        assertTrue(JoinstrTransport.isReady());
        assertNull(JoinstrTransport.unavailableReason());
    }

    @Test
    public void theGateFollowsTheCurrentStateNotACachedOne() {
        JoinstrTransport.setTorRunningForTesting(() -> false);
        assertFalse(JoinstrTransport.isReady());

        JoinstrTransport.setTorRunningForTesting(() -> true);
        assertTrue(JoinstrTransport.isReady());

        JoinstrTransport.setTorRunningForTesting(() -> false);
        assertFalse(JoinstrTransport.isReady(), "tor going away mid coinjoin must close the gate");
        assertFalse(JoinstrTransport.newCircuit());
    }
}
