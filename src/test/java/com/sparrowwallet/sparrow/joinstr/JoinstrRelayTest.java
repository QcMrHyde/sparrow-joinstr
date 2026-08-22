package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The relay in the joinstr settings page was stored and never read, so pointing it anywhere had
 * no effect and pools announced on any other relay were invisible.
 */
public class JoinstrRelayTest {

    @Test
    public void aConfiguredRelayIsUsed() {
        assertEquals("wss://relay.example", JoinstrRelay.relayOrDefault("wss://relay.example"));
        assertEquals("ws://127.0.0.1:7777", JoinstrRelay.relayOrDefault("ws://127.0.0.1:7777"));
    }

    @Test
    public void surroundingWhitespaceIsTrimmed() {
        assertEquals("wss://relay.example", JoinstrRelay.relayOrDefault("  wss://relay.example \n"));
    }

    @Test
    public void anUnsetRelayFallsBackToTheDefault() {
        assertEquals(JoinstrRelay.DEFAULT, JoinstrRelay.relayOrDefault(null));
        assertEquals(JoinstrRelay.DEFAULT, JoinstrRelay.relayOrDefault(""));
        assertEquals(JoinstrRelay.DEFAULT, JoinstrRelay.relayOrDefault("   "));
    }

    /** Anything that is not a websocket url would fail at connect time with no explanation. */
    @Test
    public void aNonWebsocketUrlFallsBackToTheDefault() {
        assertEquals(JoinstrRelay.DEFAULT, JoinstrRelay.relayOrDefault("https://relay.example"));
        assertEquals(JoinstrRelay.DEFAULT, JoinstrRelay.relayOrDefault("relay.example"));
        assertEquals(JoinstrRelay.DEFAULT, JoinstrRelay.relayOrDefault("nos.lol"));
    }

    @Test
    public void theDefaultIsAWebsocketUrl() {
        assertEquals(JoinstrRelay.DEFAULT, JoinstrRelay.relayOrDefault(JoinstrRelay.DEFAULT));
        assertTrue(JoinstrRelay.DEFAULT.startsWith("wss://"));
    }
}
