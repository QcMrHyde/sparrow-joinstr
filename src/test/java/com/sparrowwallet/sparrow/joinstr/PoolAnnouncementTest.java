package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pool announcements now arrive as decoded events rather than being scraped out of log lines,
 * which means the author of the event is known and can be checked against the key it advertises.
 */
public class PoolAnnouncementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POOL_KEY = "e37076afaf4a0054fd144f0b843c174173e7d0620a572572c0a34e6b78023afe";

    private JsonNode announcement(String extra) throws Exception {
        long timeout = Instant.now().getEpochSecond() + 3600;
        return MAPPER.readTree("{"
                + "\"id\":\"0123456789abcdef\","
                + "\"public_key\":\"" + POOL_KEY + "\","
                + "\"denomination\":0.001,"
                + "\"peers\":3,"
                + "\"timeout\":" + timeout + ","
                + "\"relay\":\"wss://nos.lol\""
                + extra + "}");
    }

    @Test
    public void anAnnouncementSignedByItsPoolKeyIsListed() throws Exception {
        JoinstrPool pool = OtherPoolsController.parsePool(announcement(""), POOL_KEY);

        assertNotNull(pool);
        assertEquals(POOL_KEY, pool.getPubkey());
        assertEquals("0123456789abcdef", pool.getPoolId());
        assertEquals("wss://nos.lol", pool.getRelay());
        assertTrue(pool.isJoinable());
    }

    /**
     * Without this anyone can announce a pool naming someone else's key, and a joiner then sends
     * its request to whoever holds that key. The reference implementation drops these too.
     */
    @Test
    public void anAnnouncementSignedByAnotherKeyIsIgnored() throws Exception {
        String someoneElse = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

        assertNull(OtherPoolsController.parsePool(announcement(""), someoneElse));
    }

    @Test
    public void theAuthorCheckIsCaseInsensitive() throws Exception {
        assertNotNull(OtherPoolsController.parsePool(announcement(""), POOL_KEY.toUpperCase()));
    }

    @Test
    public void anExpiredPoolIsNotListed() throws Exception {
        JsonNode expired = MAPPER.readTree("{"
                + "\"id\":\"abc\",\"public_key\":\"" + POOL_KEY + "\",\"denomination\":0.001,"
                + "\"peers\":3,\"timeout\":" + (Instant.now().getEpochSecond() - 10) + ","
                + "\"relay\":\"wss://nos.lol\"}");

        assertNull(OtherPoolsController.parsePool(expired, POOL_KEY));
    }

    /** A pool open longer than an hour used to be filtered out by the announcement's age. */
    @Test
    public void aLongLivedPoolIsStillListed() throws Exception {
        JsonNode longLived = MAPPER.readTree("{"
                + "\"id\":\"abc\",\"public_key\":\"" + POOL_KEY + "\",\"denomination\":0.001,"
                + "\"peers\":3,\"timeout\":" + (Instant.now().getEpochSecond() + 86400) + ","
                + "\"relay\":\"wss://nos.lol\"}");

        assertNotNull(OtherPoolsController.parsePool(longLived, POOL_KEY));
    }

    @Test
    public void anAnnouncementMissingRequiredFieldsIsIgnored() throws Exception {
        assertNull(OtherPoolsController.parsePool(MAPPER.readTree("{}"), POOL_KEY));
        assertNull(OtherPoolsController.parsePool(null, POOL_KEY));
        assertNull(OtherPoolsController.parsePool(MAPPER.readTree(
                "{\"timeout\":" + (Instant.now().getEpochSecond() + 60) + "}"), POOL_KEY));
    }

    @Test
    public void anUnsupportedPoolIsListedButNotJoinable() throws Exception {
        JoinstrPool pool = OtherPoolsController.parsePool(
                announcement(",\"transport\":\"vpn\""), POOL_KEY);

        assertNotNull(pool, "an unsupported pool should still be listed with a reason");
        assertFalse(pool.isJoinable());
        assertEquals("Unsupported", pool.getStatus());
        assertEquals(PoolSupport.REQUIRES_VPN, pool.getUnsupportedReason());
    }

    @Test
    public void theFeeRateAndPoolIdAreCarriedThrough() throws Exception {
        JoinstrPool pool = OtherPoolsController.parsePool(announcement(",\"fee_rate\":2.5"), POOL_KEY);

        assertNotNull(pool);
        assertEquals(2.5, pool.getParsedFeeRate());
        assertEquals("0123456789abcdef", pool.getPoolId());
    }

    /** Announcements from before the author check are still parsed when no author is known. */
    @Test
    public void aMissingAuthorDoesNotRejectTheAnnouncement() throws Exception {
        assertNotNull(OtherPoolsController.parsePool(announcement(""), null));
    }
}
