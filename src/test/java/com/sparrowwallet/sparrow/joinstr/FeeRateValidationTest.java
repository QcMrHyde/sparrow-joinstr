package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FeeRateValidationTest {

    @Test
    public void acceptsFeeRateWithinToleranceOfAdvertised() {
        // advertised 4 -> band [2, 8]
        assertTrue(JoinPoolHandler.isFeeRateAcceptable(4, 4));
        assertTrue(JoinPoolHandler.isFeeRateAcceptable(2, 4));
        assertTrue(JoinPoolHandler.isFeeRateAcceptable(8, 4));
    }

    @Test
    public void rejectsFeeRateOutsideToleranceOfAdvertised() {
        // advertised 4 -> band [2, 8]
        assertFalse(JoinPoolHandler.isFeeRateAcceptable(1, 4));
        assertFalse(JoinPoolHandler.isFeeRateAcceptable(9, 4));
    }

    @Test
    public void defaultPoolFeeRateBandIsOneToTwo() {
        // advertised 1 -> band [max(1,0), min(100,2)] = [1, 2]
        assertTrue(JoinPoolHandler.isFeeRateAcceptable(1, 1));
        assertTrue(JoinPoolHandler.isFeeRateAcceptable(2, 1));
        assertFalse(JoinPoolHandler.isFeeRateAcceptable(3, 1));
        assertFalse(JoinPoolHandler.isFeeRateAcceptable(0, 1));
    }

    @Test
    public void absoluteCapAppliesRegardlessOfAdvertised() {
        // advertised 60 -> hi clamped to 100, not 120
        assertTrue(JoinPoolHandler.isFeeRateAcceptable(100, 60));
        assertFalse(JoinPoolHandler.isFeeRateAcceptable(101, 60));
        // an absurd advertised rate leaves an empty band -> everything rejected
        assertFalse(JoinPoolHandler.isFeeRateAcceptable(100, 300));
        assertFalse(JoinPoolHandler.isFeeRateAcceptable(300, 300));
    }

    @Test
    public void poolParsesAdvertisedFeeRate() {
        JoinstrPool pool = new JoinstrPool("wss://nos.lol", "pk", "0.01", "3", "0");
        assertEquals(1, pool.getParsedFeeRate()); // default when unset
        pool.setFeeRate("7");
        assertEquals(7, pool.getParsedFeeRate());
    }

    @Test
    public void poolParsesFractionalAdvertisedFeeRate() {
        JoinstrPool pool = new JoinstrPool("wss://nos.lol", "pk", "0.01", "3", "0");
        pool.setFeeRate("2.5");
        assertEquals(2.5, pool.getParsedFeeRate());
        // a whole rate written as a decimal is what the electrum plugin usually publishes
        pool.setFeeRate("3.0");
        assertEquals(3.0, pool.getParsedFeeRate());
    }

    @Test
    public void fractionalRatesAreComparedAgainstTheAdvertisedBand() {
        assertTrue(JoinPoolHandler.isFeeRateAcceptable(3.0, 2.5));
        assertTrue(JoinPoolHandler.isFeeRateAcceptable(2.5, 2.5));
        assertFalse(JoinPoolHandler.isFeeRateAcceptable(5.1, 2.5));
        // the advertised rate no longer collapses to 1, so a normal pool is not refused
        assertTrue(JoinPoolHandler.isFeeRateAcceptable(3.0, 3.0));
    }

    @Test
    public void nonFiniteRatesAreRefused() {
        assertFalse(JoinPoolHandler.isFeeRateAcceptable(Double.NaN, 4));
        assertFalse(JoinPoolHandler.isFeeRateAcceptable(4, Double.POSITIVE_INFINITY));
    }

    @Test
    public void poolFeeRateDefaultsOnMalformedValue() {
        JoinstrPool pool = new JoinstrPool("wss://nos.lol", "pk", "0.01", "3", "0");
        pool.setFeeRate("abc");
        assertEquals(1, pool.getParsedFeeRate());
        pool.setFeeRate("");
        assertEquals(1, pool.getParsedFeeRate());
        pool.setFeeRate(null);
        assertEquals(1, pool.getParsedFeeRate());
        pool.setFeeRate("0");
        assertEquals(1, pool.getParsedFeeRate());
        pool.setFeeRate("-4");
        assertEquals(1, pool.getParsedFeeRate());
    }

    @Test
    public void feeRateIsRenderedWithoutATrailingZero() {
        assertEquals("3", CoinjoinMath.formatFeeRate(3.0));
        assertEquals("2.5", CoinjoinMath.formatFeeRate(2.5));
    }
}
