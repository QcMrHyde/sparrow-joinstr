package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class JoinstrMessageTest {

    @Test
    public void outputMessageRoundTrips() {
        JoinstrMessage message = new JoinstrMessage();
        message.setType("output");
        message.setAddress("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq");

        JoinstrMessage parsed = JoinstrMessage.fromJson(message.toJson());

        assertEquals("output", parsed.getType());
        assertEquals("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", parsed.getAddress());
        assertNull(parsed.getPsbt());
    }

    @Test
    public void inputMessageRoundTrips() {
        JoinstrMessage message = new JoinstrMessage();
        message.setType("input");
        message.setPsbt("cHNidP8BAAAAAA==");

        JoinstrMessage parsed = JoinstrMessage.fromJson(message.toJson());

        assertEquals("input", parsed.getType());
        assertEquals("cHNidP8BAAAAAA==", parsed.getPsbt());
    }

    /**
     * The wire format uses snake_case keys (private_key, fee_rate) so that pools are
     * interoperable with other joinstr clients. Guard against an accidental rename to
     * camelCase which would silently break credential exchange.
     */
    @Test
    public void credentialsUseSnakeCaseWireKeys() {
        JoinstrMessage message = new JoinstrMessage();
        message.setType("credentials");
        message.setPrivateKey("deadbeef");
        message.setFeeRate(5.0);

        String json = message.toJson();

        assertTrue(json.contains("\"private_key\""), "expected snake_case private_key in: " + json);
        assertTrue(json.contains("\"fee_rate\""), "expected snake_case fee_rate in: " + json);
        assertFalse(json.contains("privateKey"), "wire format must not use camelCase privateKey");
        assertFalse(json.contains("feeRate"), "wire format must not use camelCase feeRate");
    }

    @Test
    public void parsesForeignCredentialsJson() {
        String json = "{\"type\":\"credentials\",\"private_key\":\"deadbeef\",\"fee_rate\":7}";

        JoinstrMessage parsed = JoinstrMessage.fromJson(json);

        assertEquals("credentials", parsed.getType());
        assertEquals("deadbeef", parsed.getPrivateKey());
        assertEquals(7.0, parsed.getFeeRate());
    }

    /**
     * The electrum plugin derives fee_rate from its own estimator (fee_per_kb / 1000), so the
     * value on the wire is usually fractional. Parsing it must not throw.
     */
    @Test
    public void parsesFractionalFeeRate() {
        String json = "{\"type\":\"credentials\",\"private_key\":\"deadbeef\",\"fee_rate\":2.5}";

        JoinstrMessage parsed = JoinstrMessage.fromJson(json);

        assertEquals(2.5, parsed.getFeeRate());
        assertEquals("deadbeef", parsed.getPrivateKey());
    }

    @Test
    public void parsesWholeFeeRateWrittenAsDecimal() {
        JoinstrMessage parsed = JoinstrMessage.fromJson(
                "{\"type\":\"credentials\",\"private_key\":\"deadbeef\",\"fee_rate\":3.0}");

        assertEquals(3.0, parsed.getFeeRate());
    }

    @Test
    public void unsetOptionalFieldsAreNull() {
        JoinstrMessage parsed = JoinstrMessage.fromJson("{\"type\":\"output\"}");

        assertEquals("output", parsed.getType());
        assertNull(parsed.getAddress());
        assertNull(parsed.getPsbt());
        assertNull(parsed.getPrivateKey());
        assertNull(parsed.getFeeRate());
    }
}
