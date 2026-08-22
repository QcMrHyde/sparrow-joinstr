package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A pool creator recognised a join request by looking for the literal text
 * {@code "type": "join_pool"} in the decrypted payload, which depended on the sender's json
 * whitespace. A peer serialising compactly was never answered, and never told why.
 */
public class JoinRequestParsingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void aJoinRequestIsRecognisedWhateverTheSpacing() {
        assertTrue(JoinstrMessage.isJoinRequest("{\"type\": \"join_pool\"}"));
        assertTrue(JoinstrMessage.isJoinRequest("{\"type\":\"join_pool\"}"));
        assertTrue(JoinstrMessage.isJoinRequest("{\n  \"type\"  :  \"join_pool\"\n}"));
        assertTrue(JoinstrMessage.isJoinRequest("{\"version\":\"1\",\"type\":\"join_pool\"}"));
    }

    /** Field order is not fixed on the wire either. */
    @Test
    public void aJoinRequestIsRecognisedWhateverTheFieldOrder() {
        assertTrue(JoinstrMessage.isJoinRequest("{\"npub\":\"abc\",\"type\":\"join_pool\"}"));
    }

    @Test
    public void thisClientsOwnJoinRequestIsRecognised() {
        assertTrue(JoinstrMessage.isJoinRequest(JoinstrMessage.of("join_pool").toJson()));
    }

    @Test
    public void otherMessagesAreNotJoinRequests() {
        assertFalse(JoinstrMessage.isJoinRequest("{\"type\":\"output\",\"address\":\"bc1q\"}"));
        assertFalse(JoinstrMessage.isJoinRequest("{\"type\":\"reject\",\"reason\":\"missing_proof\"}"));
        assertFalse(JoinstrMessage.isJoinRequest("{}"));
    }

    /** A payload merely mentioning the words must not be treated as a request. */
    @Test
    public void textMentioningJoinPoolIsNotARequest() {
        assertFalse(JoinstrMessage.isJoinRequest("{\"type\":\"output\",\"address\":\"join_pool\"}"));
        assertFalse(JoinstrMessage.isJoinRequest("this mentions \"type\": \"join_pool\" but is not json"));
    }

    @Test
    public void unparseableInputIsNotARequest() {
        assertFalse(JoinstrMessage.isJoinRequest("not json"));
        assertFalse(JoinstrMessage.isJoinRequest(""));
        assertFalse(JoinstrMessage.isJoinRequest("[1,2,3]"));
    }

    @Test
    public void outgoingMessagesCarryTheProtocolVersion() throws Exception {
        for(String type : new String[] {"output", "input", "join_pool"}) {
            JsonNode sent = MAPPER.readTree(JoinstrMessage.of(type).toJson());
            assertEquals("1", sent.get("version").asText(), type + " is missing a version");
            assertEquals(type, sent.get("type").asText());
        }
    }

    @Test
    public void credentialsCarryTheVersionAndType() throws Exception {
        JoinstrPool pool = new JoinstrPool("wss://nos.lol", "pk", "0.001", "3", "1750000000", "aabbcc");
        pool.setPoolId("abc123");

        JsonNode sent = MAPPER.readTree(new Gson().toJson(pool.toCredentials()));

        assertEquals("1", sent.get("version").asText());
        assertEquals("credentials", sent.get("type").asText());
        // and the fields a joiner checks are still there and unchanged
        assertEquals("abc123", sent.get("id").asText());
        assertEquals(0.001, sent.get("denomination").asDouble());
        assertEquals(3, sent.get("peers").asInt());
    }

    @Test
    public void anIncomingMessageWithoutAVersionIsStillParsed() {
        JoinstrMessage parsed = JoinstrMessage.fromJson("{\"type\":\"output\",\"address\":\"bc1q\"}");

        assertEquals("output", parsed.getType());
        assertNull(parsed.getVersion());
    }
}
