package com.sparrowwallet.sparrow.joinstr;

import java.util.Collection;

/**
 * Chooses the UTXO an aut-ct proof is made against.
 *
 * It only proves membership of the keyset and is never spent, so it is deliberately a different
 * coin from the one contributed to the coinjoin.
 */
public final class ProvingUtxo {

    /** A candidate coin, reduced to what the requirement is judged on. */
    public record Candidate(String address, long value, int confirmations) {
    }

    private ProvingUtxo() {
    }

    /** Whether an address is of the script type a pool demands. Empty means any. */
    public static boolean matchesScriptType(String address, String scriptType) {
        if (scriptType == null || scriptType.isBlank()) {
            return true;
        }
        if (address == null) {
            return false;
        }

        return switch (scriptType) {
            case "p2tr" -> address.startsWith("bc1p") || address.startsWith("tb1p")
                    || address.startsWith("bcrt1p");
            case "p2wpkh" -> address.startsWith("bc1q") || address.startsWith("tb1q")
                    || address.startsWith("bcrt1q");
            case "p2pkh" -> address.startsWith("1") || address.startsWith("m")
                    || address.startsWith("n");
            case "p2sh" -> address.startsWith("3") || address.startsWith("2");
            default -> false;
        };
    }

    public static boolean qualifies(Candidate candidate, long minAmount, int minConfirmations,
            String scriptType) {
        if (candidate == null) {
            return false;
        }
        if (candidate.value() < minAmount) {
            return false;
        }
        // an unconfirmed coin may not be in the keyset snapshot at all
        if (candidate.confirmations() <= 0 || candidate.confirmations() < minConfirmations) {
            return false;
        }
        return matchesScriptType(candidate.address(), scriptType);
    }

    /** The first coin that meets the requirement, or null when the wallet has none. */
    public static Candidate select(Collection<Candidate> candidates, long minAmount,
            int minConfirmations, String scriptType) {
        if (candidates == null) {
            return null;
        }

        for (Candidate candidate : candidates) {
            if (qualifies(candidate, minAmount, minConfirmations, scriptType)) {
                return candidate;
            }
        }
        return null;
    }
}
