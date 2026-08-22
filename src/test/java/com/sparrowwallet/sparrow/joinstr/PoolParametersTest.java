package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nostr.id.Identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every pool advertised a fee rate of 1 sat/vB and expired one hour after creation, neither of
 * which the creator could change.
 */
public class PoolParametersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void aFeeRateInsideTheBandIsAccepted() {
        assertNull(PoolParameters.feeRateError("1"));
        assertNull(PoolParameters.feeRateError("2.5"));
        assertNull(PoolParameters.feeRateError("100"));
        assertEquals(2.5, PoolParameters.feeRateOrDefault("2.5", 7));
    }

    /** The band matches what a joiner will accept, so an unjoinable pool cannot be created. */
    @Test
    public void aFeeRateOutsideTheBandIsRejected() {
        assertNotNull(PoolParameters.feeRateError("0"));
        assertNotNull(PoolParameters.feeRateError("0.5"));
        assertNotNull(PoolParameters.feeRateError("101"));
        assertNotNull(PoolParameters.feeRateError("-3"));
        assertNotNull(PoolParameters.feeRateError("abc"));
    }

    @Test
    public void aBlankFeeRateTakesTheDefault() {
        assertNull(PoolParameters.feeRateError(""));
        assertNull(PoolParameters.feeRateError(null));
        assertEquals(7.0, PoolParameters.feeRateOrDefault("", 7));
        assertEquals(7.0, PoolParameters.feeRateOrDefault(null, 7));
        assertEquals(7.0, PoolParameters.feeRateOrDefault("   ", 7));
    }

    @Test
    public void aTimeoutInsideTheBandIsAccepted() {
        assertNull(PoolParameters.timeoutError("5"));
        assertNull(PoolParameters.timeoutError("60"));
        assertNull(PoolParameters.timeoutError("1440"));
        assertEquals(3600L, PoolParameters.timeoutSeconds("60", 30));
    }

    @Test
    public void aTimeoutOutsideTheBandIsRejected() {
        assertNotNull(PoolParameters.timeoutError("4"));
        assertNotNull(PoolParameters.timeoutError("1441"));
        assertNotNull(PoolParameters.timeoutError("0"));
        assertNotNull(PoolParameters.timeoutError("-10"));
        assertNotNull(PoolParameters.timeoutError("1.5"));
    }

    @Test
    public void aBlankTimeoutTakesTheDefault() {
        assertNull(PoolParameters.timeoutError(""));
        assertEquals(PoolParameters.DEFAULT_TIMEOUT_MINUTES * 60,
                PoolParameters.timeoutSeconds("", PoolParameters.DEFAULT_TIMEOUT_MINUTES));
    }

    @Test
    public void theChosenFeeRateReachesTheAnnouncement() throws Exception {
        var event = NostrPublisher.buildPoolEvent(Identity.generateRandomIdentity(), "abc123",
                "regtest", "0.001", "3", 1750000000L, "wss://nos.lol", 2.5);

        JsonNode content = MAPPER.readTree(event.getContent());

        assertEquals(2.5, content.get("fee_rate").asDouble());
        assertTrue(content.get("fee_rate").isNumber(), "fee_rate must stay a number on the wire");
    }

    @Test
    public void aWholeFeeRateIsAnnouncedWithoutATrailingZero() throws Exception {
        var event = NostrPublisher.buildPoolEvent(Identity.generateRandomIdentity(), "abc123",
                "regtest", "0.001", "3", 1750000000L, "wss://nos.lol", 3.0);

        assertEquals("3", MAPPER.readTree(event.getContent()).get("fee_rate").asText());
    }

    @Test
    public void theChosenTimeoutReachesTheAnnouncement() throws Exception {
        var event = NostrPublisher.buildPoolEvent(Identity.generateRandomIdentity(), "abc123",
                "regtest", "0.001", "3", 1750001234L, "wss://nos.lol", 1);

        assertEquals(1750001234L, MAPPER.readTree(event.getContent()).get("timeout").asLong());
    }
}
