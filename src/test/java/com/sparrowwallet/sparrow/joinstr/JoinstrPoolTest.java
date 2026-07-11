package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class JoinstrPoolTest {

    private JoinstrPool poolWithPeers(String peers) {
        return new JoinstrPool("wss://nos.lol", "pubkey", "0.01", peers, "0");
    }

    @Test
    public void parsesPlainPeerCount() {
        assertEquals(3, poolWithPeers("3").getParsedPeers());
        assertEquals(5, poolWithPeers(" 5 ").getParsedPeers());
    }

    @Test
    public void parsesConnectedOverTotalForm() {
        // "connected/total" - the total (right side) is the pool size
        assertEquals(3, poolWithPeers("1/3").getParsedPeers());
        assertEquals(5, poolWithPeers("0/5").getParsedPeers());
    }

    @Test
    public void malformedPeerStringsReturnZero() {
        assertEquals(0, poolWithPeers("").getParsedPeers());
        assertEquals(0, poolWithPeers("   ").getParsedPeers());
        assertEquals(0, poolWithPeers("abc").getParsedPeers());
        assertEquals(0, poolWithPeers("5/").getParsedPeers());
    }

    @Test
    public void peersStatusReflectsConnectedAndTotal() {
        JoinstrPool pool = poolWithPeers("3");
        assertEquals(0, pool.getConnectedPeers());
        assertEquals("0/3", pool.getPeersStatus());
    }

    @Test
    public void gettersReturnConstructorValues() {
        JoinstrPool pool = new JoinstrPool("wss://relay.example", "npub123", "0.05", "4", "1700000000");
        assertEquals("wss://relay.example", pool.getRelay());
        assertEquals("npub123", pool.getPubkey());
        assertEquals("0.05", pool.getDenomination());
        assertEquals("4", pool.getPeers());
        assertEquals("1700000000", pool.getTimeout());
    }

    @Test
    public void emptyPrivateKeyYieldsNoIdentity() {
        // No private key set and no handler attached: must return null rather than throw.
        JoinstrPool pool = poolWithPeers("3");
        assertNull(pool.getJoinstrIdentity());
    }
}
