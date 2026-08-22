package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The value range was the only rule applied to a selected coin. Everything else here decides
 * whether the coinjoin can complete at all, or whether it is worth what it costs, and all of it
 * is better settled before the wallet is asked for a password.
 */
public class CoinjoinInputTest {

    private static final long POOL = 100_000L;
    private static final long OUTPUT = 99_900L;
    private static final long DUST = 294L;
    private static final String OWN_ADDRESS = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
    private static final String PEER_ADDRESS = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq";

    private CoinjoinInput.Coin coin(long value, boolean confirmed, boolean spendable, String address) {
        return new CoinjoinInput.Coin(value, confirmed, spendable, address);
    }

    private String check(CoinjoinInput.Coin coin) {
        return CoinjoinInput.rejectionReason(coin, POOL, OUTPUT, DUST, Set.of(PEER_ADDRESS));
    }

    @Test
    public void aUsableCoinIsAccepted() {
        assertNull(check(coin(POOL + 500, true, true, OWN_ADDRESS)));
        assertNull(check(coin(POOL + 5000, true, true, OWN_ADDRESS)));
    }

    @Test
    public void anUnconfirmedCoinIsRejected() {
        String reason = check(coin(POOL + 1000, false, true, OWN_ADDRESS));

        assertNotNull(reason);
        assertTrue(reason.contains("not confirmed"), reason);
    }

    @Test
    public void aFrozenOrImmatureCoinIsRejected() {
        String reason = check(coin(POOL + 1000, true, false, OWN_ADDRESS));

        assertNotNull(reason);
        assertTrue(reason.contains("not spendable"), reason);
    }

    /** Paying an input's own address back out is address reuse on both sides of the coinjoin. */
    @Test
    public void aCoinOnOneOfThePoolsOutputAddressesIsRejected() {
        String reason = check(coin(POOL + 1000, true, true, PEER_ADDRESS));

        assertNotNull(reason);
        assertTrue(reason.contains("output addresses"), reason);
    }

    @Test
    public void aCoinOutsideTheValueRangeIsRejected() {
        assertNotNull(check(coin(POOL, true, true, OWN_ADDRESS)));
        assertNotNull(check(coin(POOL + 499, true, true, OWN_ADDRESS)));
        assertNotNull(check(coin(POOL + 5001, true, true, OWN_ADDRESS)));
    }

    @Test
    public void anOutputBelowDustIsRejected() {
        String reason = CoinjoinInput.rejectionReason(coin(POOL + 1000, true, true, OWN_ADDRESS),
                POOL, DUST - 1, DUST, Set.of());

        assertNotNull(reason);
        assertTrue(reason.contains("dust limit"), reason);
    }

    @Test
    public void aNonPositiveOutputIsRejected() {
        String reason = CoinjoinInput.rejectionReason(coin(POOL + 1000, true, true, OWN_ADDRESS),
                POOL, 0, DUST, Set.of());

        assertNotNull(reason);
        assertTrue(reason.contains("too small to cover the fee"), reason);
    }

    /**
     * The fee rate comes from the pool creator, so the per output fee cannot be trusted. What can
     * be checked locally is the difference between what this wallet puts in and takes out.
     */
    @Test
    public void aCoinjoinCostingMoreThanTheCapIsRejected() {
        long costly = OUTPUT - CoinjoinInput.MAX_PERSONAL_COST - 1;
        String reason = CoinjoinInput.rejectionReason(coin(POOL + 5000, true, true, OWN_ADDRESS),
                POOL, costly, DUST, Set.of());

        assertNotNull(reason);
        assertTrue(reason.contains("would cost you"), reason);
    }

    @Test
    public void aCostExactlyAtTheCapIsAllowed() {
        long input = POOL + 5000;
        long output = input - CoinjoinInput.MAX_PERSONAL_COST;

        assertNull(CoinjoinInput.rejectionReason(coin(input, true, true, OWN_ADDRESS),
                POOL, output, DUST, Set.of()));
        assertNotNull(CoinjoinInput.rejectionReason(coin(input, true, true, OWN_ADDRESS),
                POOL, output - 1, DUST, Set.of()));
    }

    /**
     * The cost cap and the fee rate ceiling meet exactly. At 100 sat/vB, the ceiling
     * JoinPoolHandler allows, the per output fee is 10000 sats and the worst case input exceeds
     * the denomination by 5000, so the coinjoin costs exactly the 15000 the cap permits. Anything
     * above that fee rate is refused, which is the property worth pinning: the two limits agree
     * rather than leaving a gap between them.
     */
    @Test
    public void theCostCapMeetsTheFeeRateCeilingExactly() {
        long input = CoinjoinMath.maxInputSats(POOL);

        long atCeiling = CoinjoinMath.outputAmount(POOL, 100, 3);
        assertEquals(CoinjoinInput.MAX_PERSONAL_COST, input - atCeiling);
        assertNull(CoinjoinInput.rejectionReason(coin(input, true, true, OWN_ADDRESS),
                POOL, atCeiling, DUST, Set.of()));

        long aboveCeiling = CoinjoinMath.outputAmount(POOL, 101, 3);
        String reason = CoinjoinInput.rejectionReason(coin(input, true, true, OWN_ADDRESS),
                POOL, aboveCeiling, DUST, Set.of());
        assertNotNull(reason, "a pool above the fee rate ceiling was accepted");
        assertTrue(reason.contains("would cost you"), reason);
    }

    @Test
    public void aMissingCoinIsRejected() {
        assertNotNull(CoinjoinInput.rejectionReason(null, POOL, OUTPUT, DUST, List.of()));
    }

    @Test
    public void aCoinWithNoDerivableAddressIsStillRangeChecked() {
        assertNull(check(coin(POOL + 1000, true, true, null)));
        assertNotNull(check(coin(POOL + 9999, true, true, null)));
    }
}
