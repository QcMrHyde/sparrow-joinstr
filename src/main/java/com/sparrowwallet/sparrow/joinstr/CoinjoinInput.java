package com.sparrowwallet.sparrow.joinstr;

import java.util.Collection;

/**
 * Whether a wallet UTXO may be contributed to a pool.
 *
 * The value range was the only rule applied before. The rest of these decide whether the coinjoin
 * can complete at all, and whether it is worth what it costs the user, both of which are better
 * settled before the wallet is asked for a password.
 */
public final class CoinjoinInput {

    /**
     * The most a coinjoin may cost a peer: the 5000 sats its input may exceed the denomination by,
     * plus the 10000 sats per participant allowed for the mining fee. The pool creator supplies
     * the fee rate, so the per output fee is not ours to trust; what can be checked locally is
     * what the coinjoin actually costs us.
     */
    public static final long MAX_PERSONAL_COST = 15000;

    /** The facts about a coin that decide whether it may be used. */
    public record Coin(long value, boolean confirmed, boolean spendable, String address) {
    }

    private CoinjoinInput() {
    }

    /** Why this coin cannot be used, or null if it can. */
    public static String rejectionReason(Coin coin, long poolAmountSats, long outputAmount, long dustLimit,
            Collection<String> poolOutputAddresses) {
        if (coin == null) {
            return "No UTXO was selected.";
        }

        if (!coin.confirmed()) {
            return "Selected UTXO is not confirmed.\n\n"
                    + "Only confirmed UTXOs can be used in a coinjoin.\n\n"
                    + "Please wait for the transaction to confirm and try again.";
        }

        if (!coin.spendable()) {
            return "Selected UTXO is not spendable by this wallet.\n\n"
                    + "It is frozen, or an immature coinbase output.\n\n"
                    + "Please select a different UTXO.";
        }

        if (coin.address() != null && poolOutputAddresses != null
                && poolOutputAddresses.contains(coin.address())) {
            return "Selected UTXO spends from one of the pool's output addresses.\n\n"
                    + "Reusing an address on both sides of a coinjoin defeats it.\n\n"
                    + "Please select a UTXO from a different address.";
        }

        long minSats = CoinjoinMath.minInputSats(poolAmountSats);
        long maxSats = CoinjoinMath.maxInputSats(poolAmountSats);
        if (coin.value() < minSats || coin.value() > maxSats) {
            return "Selected UTXO (" + coin.value() + " sats) is not within the required range.\n\n"
                    + "Pool denomination: " + poolAmountSats + " sats\n"
                    + "Required input range: " + minSats + " to " + maxSats + " sats";
        }

        if (outputAmount <= 0) {
            return "Pool denomination (" + poolAmountSats + " sats) is too small to cover the fee.\n\n"
                    + "Please use a larger denomination or a lower fee rate.";
        }

        if (outputAmount < dustLimit) {
            return "Output amount (" + outputAmount + " sats) is below the dust limit ("
                    + dustLimit + " sats).\n\n"
                    + "The transaction would be rejected by the network.\n"
                    + "Please use a larger denomination.";
        }

        long personalCost = coin.value() - outputAmount;
        if (personalCost > MAX_PERSONAL_COST) {
            return "This coinjoin would cost you " + personalCost + " sats.\n\n"
                    + "You would contribute " + coin.value() + " sats and receive " + outputAmount + " sats.\n"
                    + "The most a coinjoin may cost is " + MAX_PERSONAL_COST + " sats.\n\n"
                    + "The pool's fee rate may be set too high by its creator.";
        }

        return null;
    }
}
