package com.sparrowwallet.sparrow.joinstr;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Checks the aut-ct proofs joiners send, on behalf of a pool creator.
 *
 * One UTXO buys one slot. The key image that enforces that is the one the verifier derives from
 * the proof, never anything the joiner supplied alongside it, or a peer varies its own field and
 * takes every slot in the pool.
 */
public class AutctVerifier {

    private static final Logger logger = Logger.getLogger(AutctVerifier.class.getName());

    /** Reasons carried by a reject message, as the reference implementation names them. */
    public static final String MISSING_PROOF = "missing_proof";
    public static final String INVALID_PROOF = "invalid_proof";
    public static final String DUPLICATE_TOKEN = "duplicate_token";

    private final AutctClient client;
    private final String keyset;
    private final String context;
    private final Set<String> usedKeyImages = ConcurrentHashMap.newKeySet();

    public AutctVerifier(AutctClient client, String keyset, String context) {
        this.client = client;
        this.keyset = keyset;
        this.context = context;
    }

    /**
     * Reserve a key image before any joiner can present it.
     *
     * The creator's own proof is published with the pool, so without this the first peer to
     * replay it takes a slot for free.
     */
    public void reserve(String keyImage) {
        if (keyImage != null && !keyImage.isEmpty()) {
            usedKeyImages.add(keyImage);
        }
    }

    public boolean hasSeen(String keyImage) {
        return usedKeyImages.contains(keyImage);
    }

    public int accepted() {
        return usedKeyImages.size();
    }

    /** Why this join request must be refused, or null to accept it. */
    public String rejectionReason(String autctProof) {
        if (autctProof == null || autctProof.isBlank()) {
            logger.warning("Join request carries no aut-ct proof");
            return MISSING_PROOF;
        }

        AutctClient.Verification verification = client.verifyProof(autctProof, keyset, context);
        if (verification == null || !verification.valid() || verification.keyImage() == null) {
            logger.warning("Join request carries a proof that does not verify");
            return INVALID_PROOF;
        }

        // dedup on the verified key image, never on anything the joiner sent
        if (!usedKeyImages.add(verification.keyImage())) {
            logger.warning("Join request reuses a key image already spent in this pool");
            return DUPLICATE_TOKEN;
        }

        return null;
    }
}
