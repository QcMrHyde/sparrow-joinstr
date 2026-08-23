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
