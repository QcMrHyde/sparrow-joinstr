package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Tor proxy is handed to each nostr connection rather than set as a JVM wide system property,
 * so it no longer diverts the rest of Sparrow's traffic for as long as the joinstr window is open.
 */
public class TransportProxyTest {

    @AfterEach
    public void restoreRealTor() {
        JoinstrTransport.setTorRunningForTesting(null);
    }

    /**
     * A loopback relay cannot be reached through Tor, so forcing the proxy makes a local relay
     * unusable for testing. Nothing leaves the machine, so there is nothing to hide.
     */
    @Test
    public void aLocalRelayNeedsNoTor() {
        JoinstrTransport.setTorRunningForTesting(() -> false);

        assertTrue(JoinstrTransport.isLoopback("ws://127.0.0.1:7777"));
        assertTrue(JoinstrTransport.isLoopback("ws://localhost:7777"));
        assertTrue(JoinstrTransport.isReadyFor("ws://127.0.0.1:7777"));
        assertTrue(JoinstrTransport.newCircuitFor("ws://127.0.0.1:7777"));
        assertNull(JoinstrTransport.proxy("ws://127.0.0.1:7777"));
    }

    /** Everything else still goes through Tor, and is refused when Tor is down. */
    @Test
    public void aRemoteRelayStillNeedsTor() {
        JoinstrTransport.setTorRunningForTesting(() -> false);

        assertFalse(JoinstrTransport.isLoopback("wss://nos.lol"));
        assertFalse(JoinstrTransport.isReadyFor("wss://nos.lol"));
        assertFalse(JoinstrTransport.newCircuitFor("wss://nos.lol"));
    }

    /** A host merely containing "localhost" is not loopback. */
    @Test
    public void aLookalikeHostIsNotTreatedAsLocal() {
        JoinstrTransport.setTorRunningForTesting(() -> false);

        assertFalse(JoinstrTransport.isLoopback("wss://localhost.evil.com"));
        assertFalse(JoinstrTransport.isLoopback("wss://127.0.0.1.evil.com"));
        assertFalse(JoinstrTransport.isReadyFor("wss://localhost.evil.com"));
    }

    @Test
    public void thereIsNoProxyWhenTorIsDown() {
        JoinstrTransport.setTorRunningForTesting(() -> false);

        assertNull(JoinstrTransport.proxy());
    }

    @Test
    public void askingForAProxyDoesNotTouchJvmWideProperties() {
        JoinstrTransport.setTorRunningForTesting(() -> false);

        JoinstrTransport.proxy();

        assertNull(System.getProperty("socksProxyHost"),
                "joinstr set a JVM wide proxy, which diverts unrelated Sparrow traffic");
        assertNull(System.getProperty("socksProxyPort"));
    }
}
