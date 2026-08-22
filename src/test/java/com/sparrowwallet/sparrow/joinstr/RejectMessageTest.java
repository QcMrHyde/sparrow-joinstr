package com.sparrowwallet.sparrow.joinstr;

import nostr.id.Identity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the same entry point the relay listener drives, so these cover the dispatch that had the
 * bug rather than a helper beside it. A pool creator answers a join request it will not accept
 * with a reject carrying a reason; ignoring it left the joiner waiting until the pool timeout.
 */
public class RejectMessageTest {

    private static final String RELAY = "wss://nos.lol";
    private static final String POOL_ID = "0123456789abcdef";

    private final List<String> statuses = new ArrayList<>();
    private final List<String> dialogs = new ArrayList<>();

    private JoinPoolHandler handlerFor(Identity poolIdentity) {
        JoinstrPool pool = new JoinstrPool(RELAY, poolIdentity.getPublicKey().toString(),
                "0.001", "3", "1750000000");
        pool.setPoolId(POOL_ID);

        JoinPoolHandler handler = new JoinPoolHandler(Identity.generateRandomIdentity(), pool, statuses::add);
        handler.setErrorDialog(dialogs::add);
        return handler;
    }

    private String credentialsJson(Identity identity) {
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
        return credentials.toJson();
    }

    @Test
    public void aRejectStopsTheJoinAndSaysWhy() {
        JoinPoolHandler handler = handlerFor(Identity.generateRandomIdentity());

        handler.onDecryptedMessage("{\"type\":\"reject\",\"reason\":\"missing_proof\"}");

        assertEquals(List.of("Rejected"), statuses);
        assertEquals(1, dialogs.size());
        assertTrue(dialogs.get(0).contains("aut-ct proof is required"), dialogs.get(0));
    }

    @Test
    public void anUnknownReasonStillReachesTheUser() {
        JoinPoolHandler handler = handlerFor(Identity.generateRandomIdentity());

        handler.onDecryptedMessage("{\"type\":\"reject\",\"reason\":\"pool_full\"}");

        assertEquals(1, dialogs.size());
        assertTrue(dialogs.get(0).contains("pool_full"), dialogs.get(0));
    }

    @Test
    public void aRejectWithNoReasonStillStopsTheJoin() {
        JoinPoolHandler handler = handlerFor(Identity.generateRandomIdentity());

        handler.onDecryptedMessage("{\"type\":\"reject\"}");

        assertEquals(List.of("Rejected"), statuses);
        assertEquals(1, dialogs.size());
        assertFalse(dialogs.get(0).isEmpty());
    }

    @Test
    public void repeatedRejectsRaiseOneDialog() {
        JoinPoolHandler handler = handlerFor(Identity.generateRandomIdentity());

        handler.onDecryptedMessage("{\"type\":\"reject\",\"reason\":\"invalid_proof\"}");
        handler.onDecryptedMessage("{\"type\":\"reject\",\"reason\":\"invalid_proof\"}");
        handler.onDecryptedMessage("{\"type\":\"reject\",\"reason\":\"invalid_proof\"}");

        assertEquals(1, dialogs.size());
        assertEquals(1, statuses.size());
    }

    /** The pre-fix listener silently discarded a reject, which is what this asserts against. */
    @Test
    public void aRejectIsNotSilentlyDiscarded() {
        JoinPoolHandler handler = handlerFor(Identity.generateRandomIdentity());

        handler.onDecryptedMessage("{\"type\":\"reject\",\"reason\":\"duplicate_token\"}");

        assertFalse(statuses.isEmpty(), "a reject produced no status change at all");
        assertFalse(dialogs.isEmpty(), "a reject produced no message to the user");
    }

    @Test
    public void aRejectIsNotMistakenForCredentials() {
        Identity poolIdentity = Identity.generateRandomIdentity();
        JoinPoolHandler handler = handlerFor(poolIdentity);

        handler.onDecryptedMessage("{\"type\":\"reject\",\"reason\":\"missing_proof\"}");

        // a reject carries no private key, so nothing may have been adopted as pool credentials
        assertEquals("", handler.getPoolPrivateKey());
        assertNull(handler.getCoinjoinHandler());
    }

    @Test
    public void unparseableAndUnrelatedMessagesAreIgnored() {
        JoinPoolHandler handler = handlerFor(Identity.generateRandomIdentity());

        handler.onDecryptedMessage("not json at all");
        handler.onDecryptedMessage("{\"type\":\"output\",\"address\":\"bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq\"}");
        handler.onDecryptedMessage("{}");

        assertTrue(statuses.isEmpty(), "unrelated traffic changed the status: " + statuses);
        assertTrue(dialogs.isEmpty());
    }

    /**
     * The hijack from #53, driven through the listener rather than the check in isolation: a relay
     * observer answers the join request with a pool key it controls, copying every advertised term.
     */
    @Test
    public void credentialsFromAnotherKeyAreIgnoredAndTheJoinKeepsWaiting() {
        Identity poolIdentity = Identity.generateRandomIdentity();
        Identity attacker = Identity.generateRandomIdentity();
        JoinPoolHandler handler = handlerFor(poolIdentity);

        handler.onDecryptedMessage(credentialsJson(attacker));

        assertEquals("", handler.getPoolPrivateKey(), "an attacker's pool key was adopted");
        assertNull(handler.getCoinjoinHandler(), "the coinjoin was started with hijacked credentials");
        assertTrue(statuses.isEmpty(), "the hijack was reported as progress: " + statuses);
    }
}
