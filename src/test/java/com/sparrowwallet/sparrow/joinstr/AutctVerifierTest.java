package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One UTXO buys one slot. The key image that enforces it is the one the verifier derives, never
 * anything the joiner sent, or a peer varies its own field and takes every slot in the pool.
 */
public class AutctVerifierTest {

    /** Stands in for the aut-ct server, returning whatever verdict a test wants. */
    private static class FakeClient extends AutctClient {
        private final List<String> asked = new ArrayList<>();
        private Verification next = new Verification(true, "keyimage-a");

        FakeClient() {
            super("ws://127.0.0.1:1");
        }

        @Override
        public Verification verifyProof(String proof, String keyset, String context) {
            asked.add(proof + "|" + keyset + "|" + context);
            return next;
        }
    }

    private AutctVerifier verifier(FakeClient client) {
        return new AutctVerifier(client, "autct-830000-100000-1-2-1024.aks", "ctx");
    }

    @Test
    public void aValidProofIsAccepted() {
        FakeClient client = new FakeClient();

        assertNull(verifier(client).rejectionReason("proof-a"));
    }

    @Test
    public void aMissingProofIsRefused() {
        AutctVerifier verifier = verifier(new FakeClient());

        assertEquals(AutctVerifier.MISSING_PROOF, verifier.rejectionReason(null));
        assertEquals(AutctVerifier.MISSING_PROOF, verifier.rejectionReason(""));
        assertEquals(AutctVerifier.MISSING_PROOF, verifier.rejectionReason("   "));
    }

    @Test
    public void aProofThatDoesNotVerifyIsRefused() {
        FakeClient client = new FakeClient();
        client.next = new AutctClient.Verification(false, null);

        assertEquals(AutctVerifier.INVALID_PROOF, verifier(client).rejectionReason("proof-a"));
    }

    /** Accepted but with no key image is not something to trust either. */
    @Test
    public void anAcceptedProofWithoutAKeyImageIsRefused() {
        FakeClient client = new FakeClient();
        client.next = new AutctClient.Verification(true, null);

        assertEquals(AutctVerifier.INVALID_PROOF, verifier(client).rejectionReason("proof-a"));
    }

    /** The same UTXO cannot take a second slot, even with a different looking proof. */
    @Test
    public void aRepeatedKeyImageIsRefused() {
        FakeClient client = new FakeClient();
        AutctVerifier verifier = verifier(client);

        assertNull(verifier.rejectionReason("proof-a"));
        assertEquals(AutctVerifier.DUPLICATE_TOKEN, verifier.rejectionReason("a-different-proof"));
        assertEquals(1, verifier.accepted());
    }

    @Test
    public void differentKeyImagesEachTakeASlot() {
        FakeClient client = new FakeClient();
        AutctVerifier verifier = verifier(client);

        assertNull(verifier.rejectionReason("proof-a"));
        client.next = new AutctClient.Verification(true, "keyimage-b");
        assertNull(verifier.rejectionReason("proof-b"));

        assertEquals(2, verifier.accepted());
    }

    /**
     * The creator publishes its own proof with the pool, so the first peer to replay it would
     * otherwise take a slot for free.
     */
    @Test
    public void aReplayedCreatorProofTakesNoSlot() {
        FakeClient client = new FakeClient();
        AutctVerifier verifier = verifier(client);
        verifier.reserve("keyimage-a");

        assertEquals(AutctVerifier.DUPLICATE_TOKEN, verifier.rejectionReason("the-creators-proof"));
        assertTrue(verifier.hasSeen("keyimage-a"));
    }

    @Test
    public void reservingNothingIsHarmless() {
        AutctVerifier verifier = verifier(new FakeClient());
        verifier.reserve(null);
        verifier.reserve("");

        assertEquals(0, verifier.accepted());
        assertNull(verifier.rejectionReason("proof-a"));
    }

    /** The proof is checked against this pool's own keyset and context, not any other. */
    @Test
    public void theProofIsCheckedAgainstThisPoolsKeysetAndContext() {
        FakeClient client = new FakeClient();
        verifier(client).rejectionReason("proof-a");

        assertEquals(List.of("proof-a|autct-830000-100000-1-2-1024.aks|ctx"), client.asked);
    }
}
