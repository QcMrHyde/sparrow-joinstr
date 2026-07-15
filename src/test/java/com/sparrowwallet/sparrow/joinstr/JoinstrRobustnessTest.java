package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class JoinstrRobustnessTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    // --- B9: getParsedPeers must not NPE on a null peers value ---

    @Test
    public void parsedPeersHandlesNullValue() {
        JoinstrPool pool = new JoinstrPool("wss://nos.lol", "pk", "0.01", null, "0");
        assertEquals(0, pool.getParsedPeers());
    }

    @Test
    public void parsedPeersHandlesUsualForms() {
        assertEquals(3, new JoinstrPool("r", "p", "0.01", "3", "0").getParsedPeers());
        assertEquals(4, new JoinstrPool("r", "p", "0.01", "1/4", "0").getParsedPeers());
        assertEquals(0, new JoinstrPool("r", "p", "0.01", "abc", "0").getParsedPeers());
    }

    // --- B5: relay extraction prefers the NIP "relays" array, falls back to legacy "relay" ---

    @Test
    public void extractRelayPrefersRelaysArray() throws Exception {
        JsonNode node = json("{\"relays\":[\"wss://a\",\"wss://b\"],\"relay\":\"wss://legacy\"}");
        assertEquals("wss://a", OtherPoolsController.extractRelay(node));
    }

    @Test
    public void extractRelayFallsBackToLegacyRelay() throws Exception {
        JsonNode node = json("{\"relay\":\"wss://legacy\"}");
        assertEquals("wss://legacy", OtherPoolsController.extractRelay(node));
    }

    @Test
    public void extractRelayFallsBackWhenRelaysEmpty() throws Exception {
        JsonNode node = json("{\"relays\":[],\"relay\":\"wss://legacy\"}");
        assertEquals("wss://legacy", OtherPoolsController.extractRelay(node));
    }

    @Test
    public void extractRelayReturnsNullWhenAbsent() throws Exception {
        assertNull(OtherPoolsController.extractRelay(json("{\"denomination\":\"0.01\"}")));
    }

    // --- B5: network filtering ---

    @Test
    public void networkMatchesWhenFieldAbsent() throws Exception {
        assertTrue(OtherPoolsController.networkMatches(json("{\"relay\":\"wss://a\"}")));
    }

    @Test
    public void networkMatchesCurrentNetworkAndRejectsOthers() throws Exception {
        String current = com.sparrowwallet.drongo.Network.get().getName();
        assertTrue(OtherPoolsController.networkMatches(json("{\"network\":\"" + current + "\"}")));
        assertFalse(OtherPoolsController.networkMatches(json("{\"network\":\"" + current + "x\"}")));
    }
}
