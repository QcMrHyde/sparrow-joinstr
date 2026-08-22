package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A pool can require sybil resistance or a transport this client does not use. Joining one anyway
 * means waiting until the pool expires with nothing on screen explaining it, because the creator
 * either rejects the request or ignores it.
 *
 * Tor is a transport the protocol defines, so a Tor only client is a full participant. The pools
 * refused here are the ones that do not offer Tor at all, plus aut-ct pools.
 */
public class PoolSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode pool(String fields) throws Exception {
        return MAPPER.readTree("{\"id\":\"abc\",\"public_key\":\"pk\",\"denomination\":0.001,"
                + "\"peers\":3,\"timeout\":1750000000,\"relay\":\"wss://nos.lol\"" + fields + "}");
    }

    @Test
    public void anOrdinaryPoolIsJoinable() throws Exception {
        assertNull(PoolSupport.unsupportedReason(pool("")));
    }

    /** NIP.md makes Tor the default, so an announcement that omits transport is joinable. */
    @Test
    public void aPoolWithNoTransportFieldIsJoinable() throws Exception {
        assertNull(PoolSupport.unsupportedReason(pool(",\"autct\":null")));
        assertNull(PoolSupport.unsupportedReason(pool(",\"transport\":null")));
    }

    @Test
    public void aTorPoolInEitherShapeIsJoinable() throws Exception {
        assertNull(PoolSupport.unsupportedReason(pool(",\"transport\":\"tor\"")));
        assertNull(PoolSupport.unsupportedReason(pool(
                ",\"transport\":{\"tor\":{\"enable\":true},\"vpn\":{\"enable\":false}}")));
    }

    /** Tor being offered is enough, even if the pool also offers a VPN. */
    @Test
    public void aPoolOfferingBothTorAndVpnIsJoinable() throws Exception {
        assertNull(PoolSupport.unsupportedReason(pool(
                ",\"transport\":{\"tor\":{\"enable\":true},\"vpn\":{\"enable\":true}}")));
    }

    @Test
    public void aVpnOnlyPoolIsRefused() throws Exception {
        assertEquals(PoolSupport.REQUIRES_VPN, PoolSupport.unsupportedReason(pool(",\"transport\":\"vpn\"")));
        assertEquals(PoolSupport.REQUIRES_VPN, PoolSupport.unsupportedReason(pool(
                ",\"transport\":{\"tor\":{\"enable\":false},\"vpn\":{\"enable\":true}}")));
    }

    @Test
    public void aPoolOfferingNoTransportAtAllIsRefused() throws Exception {
        assertEquals(PoolSupport.TOR_NOT_ENABLED, PoolSupport.unsupportedReason(pool(
                ",\"transport\":{\"tor\":{\"enable\":false},\"vpn\":{\"enable\":false}}")));
        assertEquals(PoolSupport.TOR_NOT_ENABLED, PoolSupport.unsupportedReason(pool(
                ",\"transport\":\"i2p\"")));
    }

    @Test
    public void anAutctPoolIsRefused() throws Exception {
        String reason = PoolSupport.unsupportedReason(pool(
                ",\"autct\":{\"keyset\":\"autct-830000-100000-1-2-1024.aks\",\"min_amount\":100000}"));

        assertEquals(PoolSupport.REQUIRES_AUTCT, reason);
    }

    /** The field only means anything with a keyset naming the requirement. */
    @Test
    public void anEmptyAutctFieldDoesNotRefuseThePool() throws Exception {
        assertNull(PoolSupport.unsupportedReason(pool(",\"autct\":{}")));
        assertNull(PoolSupport.unsupportedReason(pool(",\"autct\":{\"keyset\":\"\"}")));
        assertNull(PoolSupport.unsupportedReason(pool(",\"autct\":{\"keyset\":\"   \"}")));
    }

    @Test
    public void autctIsReportedAheadOfTransport() throws Exception {
        String reason = PoolSupport.unsupportedReason(pool(
                ",\"autct\":{\"keyset\":\"autct-1-2-3-4-5.aks\"},\"transport\":\"vpn\""));

        // both apply, but the proof requirement is the one the user can do nothing about
        assertEquals(PoolSupport.REQUIRES_AUTCT, reason);
    }

    @Test
    public void aMissingAnnouncementIsNotRefused() {
        assertNull(PoolSupport.unsupportedReason(null));
    }

    @Test
    public void anUnsupportedPoolIsNotJoinable() {
        JoinstrPool joinable = new JoinstrPool("wss://nos.lol", "pk", "0.001", "3", "1750000000");
        assertTrue(joinable.isJoinable());
        assertNull(joinable.getUnsupportedReason());

        joinable.setUnsupportedReason(PoolSupport.REQUIRES_VPN);
        assertFalse(joinable.isJoinable());
        assertEquals(PoolSupport.REQUIRES_VPN, joinable.getUnsupportedReason());
    }
}
