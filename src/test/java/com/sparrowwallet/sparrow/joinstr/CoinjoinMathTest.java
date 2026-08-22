package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CoinjoinMathTest {

    private static final String OUR_ADDR = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq";
    private static final String PEER_ADDR = "bc1q2480xax2lt0u5a5s3x44aa323c9l44kk2v86mq";
    private static final String WRONG_ADDR = "bc1q6280xax2lt0u5a5s3x44aa323c9l44kk4v08oq";

    // --- denomination -> sats (mirrors py-joinstr test_utxo::TestDenominationRange) ---

    @Test
    public void denominationToSats() {
        assertEquals(100_000L, CoinjoinMath.denominationToSats("0.001"));
        assertEquals(1_000_000L, CoinjoinMath.denominationToSats("0.01"));
        assertEquals(100_000_000L, CoinjoinMath.denominationToSats("1"));
        assertEquals(29_000_000L, CoinjoinMath.denominationToSats("0.29"));
    }

    @Test
    public void denominationToleratesBtcSuffixAndWhitespace() {
        assertEquals(100_000L, CoinjoinMath.denominationToSats(" 0.001 BTC "));
        assertEquals(1_000_000L, CoinjoinMath.denominationToSats("0.01BTC"));
    }

    @Test
    public void denominationRejectsNonNumeric() {
        assertThrows(NumberFormatException.class, () -> CoinjoinMath.denominationToSats("abc"));
        assertThrows(NumberFormatException.class, () -> CoinjoinMath.denominationToSats(""));
    }

    @Test
    public void denominationRejectsSubSatoshiPrecision() {
        // 0.000000001 BTC is a fraction of a satoshi and must not be silently truncated.
        assertThrows(ArithmeticException.class, () -> CoinjoinMath.denominationToSats("0.000000001"));
    }

    // --- UTXO value range (mirrors py-joinstr test_utxo::TestCheckUtxoAvailability boundaries) ---

    @Test
    public void utxoRangeBoundaries() {
        long pool = 100_000L;
        assertEquals(100_500L, CoinjoinMath.minInputSats(pool));
        assertEquals(105_000L, CoinjoinMath.maxInputSats(pool));

        assertTrue(CoinjoinMath.isInputValueInRange(100_500L, pool));  // at min
        assertTrue(CoinjoinMath.isInputValueInRange(105_000L, pool));  // at max
        assertFalse(CoinjoinMath.isInputValueInRange(100_499L, pool)); // too small
        assertFalse(CoinjoinMath.isInputValueInRange(105_001L, pool)); // too large
        assertFalse(CoinjoinMath.isInputValueInRange(pool, pool));     // exactly denomination: no fee margin
    }

    // --- fee / output amount ---

    @Test
    public void outputAmountSubtractsFee() {
        long pool = 100_000L;
        // feeRate 1 sat/vB, 100 vB per peer, so 100 sats per output regardless of peer count
        assertEquals(100L, CoinjoinMath.feePerOutput(1, 3));
        assertEquals(100L, CoinjoinMath.feePerOutput(1, 5));
        assertEquals(pool - 100L, CoinjoinMath.outputAmount(pool, 1, 3));
        assertEquals(pool - 1000L, CoinjoinMath.outputAmount(pool, 10, 3));
    }

    /**
     * The per-output fee has to match what other joinstr clients derive, or the output amounts
     * differ and every registration PSBT is rejected on both sides. The reference implementation
     * computes int(fee_rate * 100 * output_ct) // output_ct.
     */
    @Test
    public void feePerOutputMatchesTheReferenceEstimate() {
        for(int peers = 2; peers <= 10; peers++) {
            for(long rate : new long[] {1, 2, 5, 13, 100}) {
                long reference = (rate * 100L * peers) / peers;
                assertEquals(reference, CoinjoinMath.feePerOutput(rate, peers),
                        "rate=" + rate + " peers=" + peers);
            }
        }
    }

    @Test
    public void outputAmountIsIndependentOfPeerCount() {
        long pool = 250_000L;
        long expected = CoinjoinMath.outputAmount(pool, 4, 2);
        for(int peers = 2; peers <= 8; peers++) {
            assertEquals(expected, CoinjoinMath.outputAmount(pool, 4, peers));
        }
    }

    @Test
    public void feePerOutputHandlesZeroPeers() {
        // Must not divide by zero.
        assertEquals(0L, CoinjoinMath.feePerOutput(5, 0));
    }

    // --- registration PSBT must not stand alone ---

    /**
     * A registration PSBT is published to the pool signed with SIGHASH_ALL | SIGHASH_ANYONECANPAY,
     * so any relay observer can broadcast it. If the single input already covered every output,
     * they would keep the difference.
     */
    @Test
    public void aNormalRegistrationIsNotSpendableAlone() {
        long pool = 100_000L;
        long output = CoinjoinMath.outputAmount(pool, 1, 3);

        // the joiner contributes pool + margin and the outputs total three shares
        assertFalse(CoinjoinMath.isSpendableAlone(pool + 500, output, 3));
        assertFalse(CoinjoinMath.isSpendableAlone(pool + 5000, output, 2));
    }

    @Test
    public void oneOutputSmallerThanTheInputIsSpendableAlone() {
        long pool = 100_000L;
        long output = CoinjoinMath.outputAmount(pool, 1, 1);

        assertTrue(CoinjoinMath.isSpendableAlone(pool + 5000, output, 1));
    }

    @Test
    public void outputsEqualToTheInputAreNotSpendableAlone() {
        // the reference implementation refuses only when the outputs are strictly less than the
        // input, so an exactly balanced transaction with no fee is not treated as a giveaway
        assertFalse(CoinjoinMath.isSpendableAlone(200_000L, 100_000L, 2));
        assertTrue(CoinjoinMath.isSpendableAlone(200_001L, 100_000L, 2));
    }

    @Test
    public void aLargeInputAgainstSmallOutputsIsSpendableAlone() {
        // an oversized input is the case the guard exists for
        assertTrue(CoinjoinMath.isSpendableAlone(10_000_000L, 99_900L, 3));
    }

    // --- sighash flag (mirrors py-joinstr test_sighash) ---

    @Test
    public void inputSighashIsAllAnyonecanpay() {
        assertEquals(0x81, CoinjoinMath.INPUT_SIGHASH.intValue());
    }

    // --- output validation (mirrors py-joinstr test_outputs / test_validation) ---

    private CoinjoinMath.OutputView out(String address, long value) {
        return new CoinjoinMath.OutputView(address, value);
    }

    @Test
    public void validOutputsAccepted() {
        List<CoinjoinMath.OutputView> outputs = List.of(out(OUR_ADDR, 100_000), out(PEER_ADDR, 100_000));
        assertTrue(CoinjoinMath.validateOutputs(outputs, List.of(OUR_ADDR, PEER_ADDR), 100_000));
    }

    @Test
    public void unexpectedAddressRejected() {
        List<CoinjoinMath.OutputView> outputs = List.of(out(WRONG_ADDR, 100_000), out(PEER_ADDR, 100_000));
        assertFalse(CoinjoinMath.validateOutputs(outputs, List.of(OUR_ADDR, PEER_ADDR), 100_000));
    }

    @Test
    public void wrongAmountRejected() {
        List<CoinjoinMath.OutputView> outputs = List.of(out(OUR_ADDR, 99_999), out(PEER_ADDR, 100_000));
        assertFalse(CoinjoinMath.validateOutputs(outputs, List.of(OUR_ADDR, PEER_ADDR), 100_000));
    }

    @Test
    public void missingExpectedOutputRejected() {
        // Only one of the two expected addresses is present.
        List<CoinjoinMath.OutputView> outputs = List.of(out(OUR_ADDR, 100_000));
        assertFalse(CoinjoinMath.validateOutputs(outputs, List.of(OUR_ADDR, PEER_ADDR), 100_000));
    }
}
