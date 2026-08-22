package com.sparrowwallet.sparrow.joinstr;

import nostr.id.Identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anyone reading the relay sees a join request and can answer it, because nip 04 does not
 * authenticate the sender. Credentials are only acceptable if they carry the private key of the
 * pool that was chosen and repeat the terms it advertised.
 */
public class CredentialsAuthTest {

    private static final String RELAY = "wss://nos.lol";
    private static final String POOL_ID = "0123456789abcdef";
    private static final String DENOMINATION = "0.001";
    private static final String PEERS = "3";
    private static final String TIMEOUT = "1750000000";

    private JoinstrPool poolFor(Identity poolIdentity) {
        JoinstrPool pool = new JoinstrPool(RELAY, poolIdentity.getPublicKey().toString(),
                DENOMINATION, PEERS, TIMEOUT);
        pool.setPoolId(POOL_ID);
        return pool;
    }

    private JoinstrMessage credentialsFrom(Identity identity) {
        JoinstrMessage credentials = new JoinstrMessage();
        credentials.setType("credentials");
        credentials.setPrivateKey(identity.getPrivateKey().toString());
        credentials.setId(POOL_ID);
        credentials.setPublicKey(identity.getPublicKey().toString());
        credentials.setDenomination(0.001);
        credentials.setPeers(3);
        credentials.setTimeout(1750000000L);
        credentials.setRelay(RELAY);
        credentials.setFeeRate(1.0);
        return credentials;
    }

    @Test
    public void acceptsCredentialsFromThePoolThatWasJoined() {
        Identity poolIdentity = Identity.generateRandomIdentity();

        assertNull(JoinPoolHandler.credentialsRejectionReason(credentialsFrom(poolIdentity),
                poolFor(poolIdentity)));
    }

    /**
     * The hijack: a relay observer answers the join request first, with a pool key it controls.
     * Every advertised term is copied, so only the key check catches it.
     */
    @Test
    public void rejectsCredentialsCarryingSomeoneElsesPoolKey() {
        Identity poolIdentity = Identity.generateRandomIdentity();
        Identity attacker = Identity.generateRandomIdentity();

        JoinstrMessage hijack = credentialsFrom(attacker);
        hijack.setPublicKey(poolIdentity.getPublicKey().toString());

        String reason = JoinPoolHandler.credentialsRejectionReason(hijack, poolFor(poolIdentity));

        assertNotNull(reason);
        assertTrue(reason.contains("not the key of this pool"), reason);
    }

    @Test
    public void rejectsAMalformedPrivateKey() {
        Identity poolIdentity = Identity.generateRandomIdentity();

        JoinstrMessage credentials = credentialsFrom(poolIdentity);
        credentials.setPrivateKey("not-a-key");

        assertNotNull(JoinPoolHandler.credentialsRejectionReason(credentials, poolFor(poolIdentity)));
    }

    @Test
    public void rejectsAChangedPoolId() {
        Identity poolIdentity = Identity.generateRandomIdentity();

        JoinstrMessage credentials = credentialsFrom(poolIdentity);
        credentials.setId("fedcba9876543210");

        assertEquals("id does not match the announcement",
                JoinPoolHandler.credentialsRejectionReason(credentials, poolFor(poolIdentity)));
    }

    @Test
    public void rejectsChangedTerms() {
        Identity poolIdentity = Identity.generateRandomIdentity();

        JoinstrMessage denomination = credentialsFrom(poolIdentity);
        denomination.setDenomination(0.01);
        assertEquals("denomination does not match the announcement",
                JoinPoolHandler.credentialsRejectionReason(denomination, poolFor(poolIdentity)));

        JoinstrMessage peers = credentialsFrom(poolIdentity);
        peers.setPeers(9);
        assertEquals("peers does not match the announcement",
                JoinPoolHandler.credentialsRejectionReason(peers, poolFor(poolIdentity)));

        JoinstrMessage timeout = credentialsFrom(poolIdentity);
        timeout.setTimeout(1799999999L);
        assertEquals("timeout does not match the announcement",
                JoinPoolHandler.credentialsRejectionReason(timeout, poolFor(poolIdentity)));

        JoinstrMessage relay = credentialsFrom(poolIdentity);
        relay.setRelay("wss://evil.example");
        assertEquals("relay does not match the announcement",
                JoinPoolHandler.credentialsRejectionReason(relay, poolFor(poolIdentity)));
    }

    /** A term the sender simply omits must not pass as a match. */
    @Test
    public void rejectsOmittedTerms() {
        Identity poolIdentity = Identity.generateRandomIdentity();

        for(String omitted : new String[] {"denomination", "peers", "timeout", "relay"}) {
            JoinstrMessage credentials = credentialsFrom(poolIdentity);
            switch(omitted) {
                case "denomination" -> credentials.setDenomination(null);
                case "peers" -> credentials.setPeers(null);
                case "timeout" -> credentials.setTimeout(null);
                case "relay" -> credentials.setRelay(null);
            }
            assertNotNull(JoinPoolHandler.credentialsRejectionReason(credentials, poolFor(poolIdentity)),
                    "omitting " + omitted + " was accepted");
        }
    }

    @Test
    public void acceptsAPoolThatNeverAnnouncedAnId() {
        Identity poolIdentity = Identity.generateRandomIdentity();
        JoinstrPool pool = new JoinstrPool(RELAY, poolIdentity.getPublicKey().toString(),
                DENOMINATION, PEERS, TIMEOUT);

        assertNull(JoinPoolHandler.credentialsRejectionReason(credentialsFrom(poolIdentity), pool));
    }
}
