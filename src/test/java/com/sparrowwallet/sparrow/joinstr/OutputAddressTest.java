package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A wallet hands out the first address it has not seen used on chain. A pool that never completes
 * leaves its address unused, so the next pool is handed the same one and two coinjoins pay the
 * same address.
 */
public class OutputAddressTest {

    @BeforeEach
    @AfterEach
    public void reset() {
        OutputAddress.resetForTesting();
    }

    /** Walks a fixed list the way the wallet walks its receive addresses. */
    private java.util.function.Supplier<String> walletOffering(List<String> addresses) {
        AtomicInteger index = new AtomicInteger();
        return () -> {
            int i = index.getAndIncrement();
            return i < addresses.size() ? addresses.get(i) : addresses.get(addresses.size() - 1);
        };
    }

    @Test
    public void theFirstPoolGetsTheFirstAddress() {
        assertEquals("a", OutputAddress.reserveFirstFree(walletOffering(List.of("a", "b", "c"))));
    }

    /** The case that matters: a pool that did not complete leaves its address unused. */
    @Test
    public void asecondPoolDoesNotGetTheSameAddress() {
        assertEquals("a", OutputAddress.reserveFirstFree(walletOffering(List.of("a", "b", "c"))));
        assertEquals("b", OutputAddress.reserveFirstFree(walletOffering(List.of("a", "b", "c"))));
        assertEquals("c", OutputAddress.reserveFirstFree(walletOffering(List.of("a", "b", "c"))));
    }

    @Test
    public void reservationsSurviveAcrossManyPools() {
        List<String> wallet = List.of("a", "b", "c", "d", "e");
        for(String expected : wallet) {
            assertEquals(expected, OutputAddress.reserveFirstFree(walletOffering(wallet)));
        }
    }

    /** When the wallet stops producing new addresses, take the last rather than loop. */
    @Test
    public void anExhaustedWalletStillReturnsAnAddress() {
        assertEquals("a", OutputAddress.reserveFirstFree(walletOffering(List.of("a"))));

        String again = OutputAddress.reserveFirstFree(walletOffering(List.of("a")));

        assertEquals("a", again, "an exhausted wallet should still yield something to pay to");
    }

    @Test
    public void anEmptyWalletYieldsNothing() {
        assertNull(OutputAddress.reserveFirstFree(() -> null));
    }
}
