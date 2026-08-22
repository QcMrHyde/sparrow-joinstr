package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Registration PSBTs are signed with SIGHASH_ALL | SIGHASH_ANYONECANPAY, so every signature
 * commits to the whole output list in order. Peers that assemble the list in different orders
 * cannot all be satisfied by one transaction.
 *
 * The electrum plugin builds its list from whatever order its relay poll returns and never sorts
 * (plugin/joinstr.py:1600-1604, :2220). Sorting the addresses lexicographically, which is what
 * this client used to do, diverges from that on almost every pool. Ordering by the time each
 * announcement was published reproduces the order the plugin's peers converge on, and unlike
 * local arrival order it is the same for everyone.
 */
public class OutputOrderingTest {

    private static final String A = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq";
    private static final String B = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
    private static final String C = "bc1q9d4ywgfnd8h43da5tpcxcn6ajv590cg6d3tg6axemvljvt2k76zs50tv4q";

    private CoinjoinHandler handler() {
        JoinstrPool pool = new JoinstrPool("wss://nos.lol", "pk", "0.001", "3", "1750000000");
        return new CoinjoinHandler(null, pool, null, null, status -> {
        });
    }

    private void output(CoinjoinHandler handler, String address, long createdAt) {
        handler.handleDecryptedMessage("{\"type\":\"output\",\"address\":\"" + address + "\"}", createdAt);
    }

    @Test
    public void outputsFollowPublicationTimeNotArrival() {
        CoinjoinHandler handler = handler();

        // B published first, then A, then C, and they arrive in yet another order.
        // Lexicographically these sort C, A, B, so the expected result below can only come
        // from publication time.
        output(handler, A, 200);
        output(handler, C, 300);
        output(handler, B, 100);

        assertEquals(List.of(B, A, C), handler.orderedOutputs());
        assertNotEquals(handler.orderedOutputs().stream().sorted().toList(),
                handler.orderedOutputs());
    }

    /** Two peers seeing the same announcements in different orders must agree. */
    @Test
    public void twoPeersSeeingDifferentArrivalOrdersAgree() {
        CoinjoinHandler first = handler();
        output(first, A, 200);
        output(first, C, 300);
        output(first, B, 100);

        CoinjoinHandler second = handler();
        output(second, C, 300);
        output(second, B, 100);
        output(second, A, 200);

        assertEquals(first.orderedOutputs(), second.orderedOutputs());
        assertEquals(List.of(B, A, C), first.orderedOutputs());
    }

    /** The old behaviour: lexicographic order, which no plugin peer produces. */
    @Test
    public void theOrderIsNotLexicographic() {
        CoinjoinHandler handler = handler();

        output(handler, B, 100);
        output(handler, A, 200);
        output(handler, C, 300);

        List<String> ordered = handler.orderedOutputs();
        List<String> lexicographic = ordered.stream().sorted().toList();

        assertEquals(List.of(B, A, C), ordered);
        assertNotEquals(lexicographic, ordered, "outputs were sorted lexicographically");
    }

    /** Same second for two announcements still has to resolve the same way everywhere. */
    @Test
    public void tiesAreBrokenDeterministically() {
        CoinjoinHandler first = handler();
        output(first, B, 100);
        output(first, A, 100);

        CoinjoinHandler second = handler();
        output(second, A, 100);
        output(second, B, 100);

        assertEquals(first.orderedOutputs(), second.orderedOutputs());
        // equal times fall back to the address, which is the only thing both peers share
        assertEquals(List.of(A, B), first.orderedOutputs());
    }

    @Test
    public void aRepeatedAnnouncementDoesNotDuplicateTheOutput() {
        CoinjoinHandler handler = handler();

        output(handler, A, 100);
        output(handler, A, 500);

        assertEquals(List.of(A), handler.orderedOutputs());
        // the first announcement's time is the one that counts, so a peer republishing later
        // cannot move itself down the list
        output(handler, B, 200);
        assertEquals(List.of(A, B), handler.orderedOutputs());
    }

    @Test
    public void outputsBeyondThePeerCountAreIgnored() {
        CoinjoinHandler handler = handler();

        output(handler, A, 100);
        output(handler, B, 200);
        output(handler, C, 300);
        output(handler, "bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3", 400);

        assertEquals(3, handler.orderedOutputs().size(), "a fourth output entered a 3 peer pool");
    }
}
