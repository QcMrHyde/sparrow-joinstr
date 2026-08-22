package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A joiner checks the credentials it is sent against the pool announcement it chose, field by
 * field, before trusting the private key inside them. These cover that the payload this client
 * sends survives that check.
 */
public class JoinstrCredentialsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Gson GSON = new Gson();

    /** The fields a joiner compares against the announcement. */
    private static final String[] CHECKED_FIELDS =
            {"id", "public_key", "denomination", "peers", "timeout", "relay"};

    private static final String ANNOUNCEMENT = "{"
            + "\"versions\":[\"1\"],"
            + "\"type\":\"new_pool\","
            + "\"id\":\"0123456789abcdef\","
            + "\"network\":\"regtest\","
            + "\"public_key\":\"e37076afaf4a0054fd144f0b843c174173e7d0620a572572c0a34e6b78023afe\","
            + "\"denomination\":0.001,"
            + "\"peers\":3,"
            + "\"timeout\":1750000000,"
            + "\"relay\":\"wss://nos.lol\","
            + "\"fee_rate\":2.5"
            + "}";

    /** Build the pool the way OtherPoolsController does when it reads an announcement. */
    private JoinstrPool poolFromAnnouncement() throws Exception {
        JsonNode a = MAPPER.readTree(ANNOUNCEMENT);
        JoinstrPool pool = new JoinstrPool(
                a.get("relay").asText(),
                a.get("public_key").asText(),
                a.get("denomination").asText(),
                a.get("peers").asText(),
                a.get("timeout").asText(),
                "aabbcc");
        pool.setFeeRate(a.get("fee_rate").asText());
        pool.setPoolId(a.get("id").asText());
        return pool;
    }

    @Test
    public void credentialsCarryEveryRequiredField() throws Exception {
        Map<String, Object> credentials = poolFromAnnouncement().toCredentials();

        for(String field : CHECKED_FIELDS) {
            assertTrue(credentials.containsKey(field), "missing field: " + field);
            assertNotNull(credentials.get(field), "null field: " + field);
        }
        // denomination and fee_rate were absent from the map that was serialised, so both went out
        // as JSON null and the credentials were refused
        assertNotNull(credentials.get("fee_rate"));
        assertNotNull(credentials.get("private_key"));
    }

    @Test
    public void credentialsIdIsTheAnnouncedPoolIdNotThePubkey() throws Exception {
        JoinstrPool pool = poolFromAnnouncement();
        Map<String, Object> credentials = pool.toCredentials();

        assertEquals("0123456789abcdef", credentials.get("id"));
        assertNotEquals(pool.getPubkey(), credentials.get("id"));
    }

    @Test
    public void credentialsMatchTheAnnouncementFieldByField() throws Exception {
        JsonNode announced = MAPPER.readTree(ANNOUNCEMENT);
        JsonNode sent = MAPPER.readTree(GSON.toJson(poolFromAnnouncement().toCredentials()));

        for(String field : CHECKED_FIELDS) {
            JsonNode a = announced.get(field);
            JsonNode s = sent.get(field);
            assertNotNull(s, "credentials dropped " + field);
            if(a.isNumber()) {
                assertEquals(a.asDouble(), s.asDouble(), field + " changed value");
                assertTrue(s.isNumber(), field + " must stay a number, was: " + s);
            } else {
                assertEquals(a.asText(), s.asText(), field + " changed value");
            }
        }
    }

    /**
     * The announcement publishes these as JSON numbers. Sending them back as quoted strings fails
     * the joiner's comparison even when the value is right.
     */
    @Test
    public void numericFieldsAreSerialisedAsNumbers() throws Exception {
        JsonNode sent = MAPPER.readTree(GSON.toJson(poolFromAnnouncement().toCredentials()));

        assertTrue(sent.get("denomination").isNumber(), "denomination was: " + sent.get("denomination"));
        assertTrue(sent.get("peers").isNumber(), "peers was: " + sent.get("peers"));
        assertTrue(sent.get("timeout").isNumber(), "timeout was: " + sent.get("timeout"));
        assertTrue(sent.get("fee_rate").isNumber(), "fee_rate was: " + sent.get("fee_rate"));
        assertTrue(sent.get("id").isTextual());
        assertTrue(sent.get("relay").isTextual());
    }

    @Test
    public void credentialsValuesArePositive() throws Exception {
        JsonNode sent = MAPPER.readTree(GSON.toJson(poolFromAnnouncement().toCredentials()));

        // a joiner rejects the credentials unless all four parse positive
        assertTrue(sent.get("denomination").asDouble() > 0);
        assertTrue(sent.get("peers").asInt() > 0);
        assertTrue(sent.get("fee_rate").asDouble() > 0);
        assertTrue(sent.get("timeout").asLong() > 0);
    }

    @Test
    public void missingPoolIdDoesNotProduceANullField() {
        JoinstrPool pool = new JoinstrPool("wss://nos.lol", "pk", "0.001", "3", "1750000000", "aabbcc");

        assertEquals("", pool.getPoolId());
        assertNotNull(pool.toCredentials().get("id"));
    }
}
