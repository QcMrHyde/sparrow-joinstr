package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.drongo.protocol.SigHash;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure coinjoin arithmetic and output validation, extracted from CoinjoinHandler so it can be
 * unit tested without a wallet, identity or relay connection.
 */
public final class CoinjoinMath {

    /** A joined UTXO must be worth at least the denomination plus this margin (fee + change buffer). */
    public static final long MIN_UTXO_MARGIN = 500;
    /** A joined UTXO must be worth at most the denomination plus this margin. */
    public static final long MAX_UTXO_MARGIN = 5000;
    /** Inputs are signed with SIGHASH_ALL | SIGHASH_ANYONECANPAY (0x81) per the joinstr NIP. */
    public static final SigHash INPUT_SIGHASH = SigHash.ANYONECANPAY_ALL;
    /**
     * Vsize charged per peer (one input plus one output) when estimating the fee. Every client in a
     * pool must use the same figure or they derive different output amounts and reject each other's
     * registration PSBTs. This matches the reference implementation, which estimates the whole
     * transaction at 100 vB per output.
     */
    private static final long ESTIMATED_VSIZE_PER_PEER = 100L;

    private CoinjoinMath() {
    }

    /**
     * Convert a denomination string (e.g. "0.001", "0.01 BTC") to satoshis.
     * Throws if the denomination is not a valid number or is not a whole number of satoshis.
     */
    public static long denominationToSats(String denomination) {
        String denomStr = denomination.replace(" BTC", "").replace("BTC", "").trim();
        BigDecimal denom = new BigDecimal(denomStr);
        return denom.movePointRight(8).longValueExact();
    }

    public static long minInputSats(long poolAmountSats) {
        return poolAmountSats + MIN_UTXO_MARGIN;
    }

    public static long maxInputSats(long poolAmountSats) {
        return poolAmountSats + MAX_UTXO_MARGIN;
    }

    public static boolean isInputValueInRange(long value, long poolAmountSats) {
        return value >= minInputSats(poolAmountSats) && value <= maxInputSats(poolAmountSats);
    }

    public static long feePerOutput(double feeRate, int numPeers) {
        if (numPeers <= 0) {
            return 0;
        }
        long estimatedTxSize = ESTIMATED_VSIZE_PER_PEER * numPeers;
        long totalFee = (long) (feeRate * estimatedTxSize);
        return totalFee / numPeers;
    }

    public static long outputAmount(long poolAmountSats, double feeRate, int numPeers) {
        return poolAmountSats - feePerOutput(feeRate, numPeers);
    }

    /** Render a fee rate without a trailing ".0" when it is a whole number of sat/vB. */
    public static String formatFeeRate(double feeRate) {
        if (feeRate == Math.rint(feeRate) && Double.isFinite(feeRate)) {
            return String.valueOf((long) feeRate);
        }
        return new java.math.BigDecimal(feeRate).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    /** An output reduced to the two fields coinjoin validation cares about. */
    public record OutputView(String address, long value) {
    }

    /**
     * Every output must pay one of the expected addresses exactly {@code expectedAmount} sats, and
     * all expected addresses must be present exactly once. Mirrors the equal-value coinjoin rule.
     */
    public static boolean validateOutputs(List<OutputView> outputs, Collection<String> expectedAddresses,
            long expectedAmount) {
        Set<String> expected = new HashSet<>(expectedAddresses);
        Set<String> found = new HashSet<>();

        for (OutputView output : outputs) {
            if (!expected.contains(output.address())) {
                return false;
            }
            if (output.value() != expectedAmount) {
                return false;
            }
            found.add(output.address());
        }

        return found.size() == expected.size();
    }
}
