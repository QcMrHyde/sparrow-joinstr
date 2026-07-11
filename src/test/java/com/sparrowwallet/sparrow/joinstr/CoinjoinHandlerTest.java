package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies CoinjoinHandler still derives the pool amount and peer count correctly after the
 * arithmetic was moved into CoinjoinMath. The constructor touches neither wallet nor identity,
 * so nulls are safe here.
 */
public class CoinjoinHandlerTest {

    private CoinjoinHandler handlerFor(String denomination, String peers) {
        JoinstrPool pool = new JoinstrPool("wss://nos.lol", "pubkey", denomination, peers, "0");
        return new CoinjoinHandler(null, pool, null, null, null);
    }

    @Test
    public void derivesPoolAmountFromDenomination() {
        CoinjoinHandler handler = handlerFor("0.01", "3");
        try {
            assertEquals(1_000_000L, handler.getPoolAmountSats());
            assertEquals(3, handler.getNumPeers());
            assertFalse(handler.isReadyForInputPhase());
            assertTrue(handler.getOutputAddresses().isEmpty());
        } finally {
            handler.stopListening();
        }
    }

    @Test
    public void parsesPeerCountFromConnectedOverTotalForm() {
        CoinjoinHandler handler = handlerFor("0.001", "1/4");
        try {
            assertEquals(100_000L, handler.getPoolAmountSats());
            assertEquals(4, handler.getNumPeers());
        } finally {
            handler.stopListening();
        }
    }
}
