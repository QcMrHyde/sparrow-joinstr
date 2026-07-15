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
        // feeRate 1 sat/vB, 150 vB per peer -> 150 sats fee per output regardless of peer count
        assertEquals(150L, CoinjoinMath.feePerOutput(1, 3));
        assertEquals(150L, CoinjoinMath.feePerOutput(1, 5));
        assertEquals(pool - 150L, CoinjoinMath.outputAmount(pool, 1, 3));
        assertEquals(pool - 1500L, CoinjoinMath.outputAmount(pool, 10, 3));
    }

    @Test
    public void feePerOutputHandlesZeroPeers() {
        // Must not divide by zero.
        assertEquals(0L, CoinjoinMath.feePerOutput(5, 0));
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
