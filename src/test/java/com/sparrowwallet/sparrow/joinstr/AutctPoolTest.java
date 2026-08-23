package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nostr.id.Identity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirrors the behaviour the reference implementation pins in its own aut-ct tests: the keyset
 * name carries the requirement, the proof context binds the pool id to the pool key, and the
 * requirement a pool advertised cannot be dropped or swapped in its credentials.
 */
public class AutctPoolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEYSET = "autct-830000-100000-6-2-1024.aks";

    // --- keyset names ---

    @Test
    public void onlyTheCanonicalKeysetNameIsAccepted() {
        assertTrue(AutctPool.isValidKeyset(KEYSET));
        assertFalse(AutctPool.isValidKeyset("keyset.txt"));
        assertFalse(AutctPool.isValidKeyset("../../etc/passwd"));
        assertFalse(AutctPool.isValidKeyset("autct-830000-100000-6-2-1024.aks; rm -rf /"));
        assertFalse(AutctPool.isValidKeyset(null));
    }

    /** The name reaches an autct server as a command argument, so nothing else may pass. */
    @Test
    public void aKeysetNameCannotCarryAPath() {
        assertFalse(AutctPool.isValidKeyset("/keysets/autct-1-2-3-4-5.aks"));
        assertFalse(AutctPool.isValidKeyset("sub/autct-1-2-3-4-5.aks"));
    }

    @Test
    public void theRequirementComesOutOfTheKeysetName() throws Exception {
        JsonNode autct = MAPPER.readTree(AutctPool.announcementJson(KEYSET));

        assertEquals(KEYSET, autct.get("keyset").asText());
        assertEquals(100000, autct.get("min_amount").asLong());
        assertEquals(6, autct.get("min_confirmations").asInt());
        assertEquals("p2tr", autct.get("script_type").asText());
    }

    @Test
    public void anInvalidKeysetProducesNoAnnouncement() {
        assertNull(AutctPool.announcementJson("nonsense"));
        assertNull(AutctPool.announcementJson(""));
    }

    // --- reading a pool's requirement ---

    private JsonNode pool(String autct) throws Exception {
        return MAPPER.readTree("{\"id\":\"abc\",\"public_key\":\"pk\"" + autct + "}");
    }

    @Test
    public void aPoolWithoutAutctDemandsNothing() throws Exception {
        assertNull(AutctPool.keysetOf(pool("")));
        assertNull(AutctPool.keysetOf(pool(",\"autct\":null")));
        assertNull(AutctPool.keysetOf(null));
    }

    @Test
    public void aPoolNamingAnInvalidKeysetIsTreatedAsNamingNone() throws Exception {
        assertNull(AutctPool.keysetOf(pool(",\"autct\":{\"keyset\":\"../evil\"}")));
    }

    @Test
    public void thePoolsRequirementIsRead() throws Exception {
        JsonNode data = pool(",\"autct\":{\"keyset\":\"" + KEYSET
                + "\",\"min_amount\":100000,\"min_confirmations\":6}");

        assertEquals(KEYSET, AutctPool.keysetOf(data));
        assertEquals(100000, AutctPool.minAmount(data));
        assertEquals(6, AutctPool.minConfirmations(data));
    }

    // --- proof context ---

    @Test
    public void theContextIsHexAndFitsAServerArgument() {
        String context = AutctPool.context("abc123", "pk");

        assertEquals(64, context.length());
        assertTrue(context.matches("[0-9a-f]{64}"));
    }

    /** A proof published in one pool must not be usable by one that copies its id. */
    @Test
    public void copyingTheIdAloneGivesADifferentContext() {
        assertNotEquals(AutctPool.context("abc123", "poolkey"),
                AutctPool.context("abc123", "another-poolkey"));
    }

    @Test
    public void copyingThePoolKeyAloneGivesADifferentContext() {
        assertNotEquals(AutctPool.context("abc123", "poolkey"),
                AutctPool.context("different-id", "poolkey"));
    }

    @Test
    public void theSamePoolAlwaysGivesTheSameContext() {
        assertEquals(AutctPool.context("abc123", "poolkey"), AutctPool.context("abc123", "poolkey"));
    }

    // --- the advertised requirement survives into the credentials ---

    private JoinstrPool advertised(Identity poolIdentity) {
        JoinstrPool pool = new JoinstrPool("wss://nos.lol", poolIdentity.getPublicKey().toString(),
                "0.001", "3", "1750000000");
        pool.setPoolId("abc123");
        pool.setAutctKeyset(KEYSET);
        return pool;
    }

    private JoinstrMessage credentials(Identity poolIdentity, String keyset) {
        JoinstrMessage message = new JoinstrMessage();
        message.setType("credentials");
        message.setPrivateKey(poolIdentity.getPrivateKey().toString());
        message.setId("abc123");
        message.setDenomination(0.001);
        message.setPeers(3);
        message.setTimeout(1750000000L);
        message.setRelay("wss://nos.lol");
        if(keyset != null) {
            message.setAutctKeyset(keyset);
        }
        return message;
    }

    @Test
    public void credentialsRepeatingTheRequirementAreAccepted() {
        Identity poolIdentity = Identity.generateRandomIdentity();

        assertNull(JoinPoolHandler.credentialsRejectionReason(
                credentials(poolIdentity, KEYSET), advertised(poolIdentity)));
    }

    /** Dropping it would put the joiner in a pool with no sybil resistance at all. */
    @Test
    public void credentialsDroppingTheRequirementAreRefused() {
        Identity poolIdentity = Identity.generateRandomIdentity();

        String reason = JoinPoolHandler.credentialsRejectionReason(
                credentials(poolIdentity, null), advertised(poolIdentity));

        assertNotNull(reason);
        assertTrue(reason.contains("aut-ct"), reason);
    }

    @Test
    public void credentialsSwappingTheKeysetAreRefused() {
        Identity poolIdentity = Identity.generateRandomIdentity();

        assertNotNull(JoinPoolHandler.credentialsRejectionReason(
                credentials(poolIdentity, "autct-830000-1-1-1-1.aks"), advertised(poolIdentity)));
    }

    @Test
    public void aPoolWithoutARequirementDoesNotDemandOneInCredentials() {
        Identity poolIdentity = Identity.generateRandomIdentity();
        JoinstrPool plain = new JoinstrPool("wss://nos.lol", poolIdentity.getPublicKey().toString(),
                "0.001", "3", "1750000000");
        plain.setPoolId("abc123");

        assertNull(JoinPoolHandler.credentialsRejectionReason(
                credentials(poolIdentity, null), plain));
    }

    // --- choosing the proving utxo ---

    @Test
    public void theProvingUtxoMustBeBigEnoughAndConfirmed() {
        ProvingUtxo.Candidate small = new ProvingUtxo.Candidate("bcrt1p" + "x".repeat(10), 50_000, 10);
        ProvingUtxo.Candidate unconfirmed = new ProvingUtxo.Candidate("bcrt1p" + "x".repeat(10), 200_000, 0);
        ProvingUtxo.Candidate tooNew = new ProvingUtxo.Candidate("bcrt1p" + "x".repeat(10), 200_000, 2);
        ProvingUtxo.Candidate good = new ProvingUtxo.Candidate("bcrt1p" + "x".repeat(10), 200_000, 10);

        assertFalse(ProvingUtxo.qualifies(small, 100_000, 6, "p2tr"));
        assertFalse(ProvingUtxo.qualifies(unconfirmed, 100_000, 6, "p2tr"));
        assertFalse(ProvingUtxo.qualifies(tooNew, 100_000, 6, "p2tr"));
        assertTrue(ProvingUtxo.qualifies(good, 100_000, 6, "p2tr"));
    }

    @Test
    public void theProvingUtxoMustBeTheRightScriptType() {
        assertTrue(ProvingUtxo.matchesScriptType("bcrt1pabc", "p2tr"));
        assertTrue(ProvingUtxo.matchesScriptType("bc1pabc", "p2tr"));
        assertFalse(ProvingUtxo.matchesScriptType("bc1qabc", "p2tr"));
        assertFalse(ProvingUtxo.matchesScriptType("1abc", "p2tr"));
        // an empty script type means the pool does not care
        assertTrue(ProvingUtxo.matchesScriptType("bc1qabc", ""));
        assertTrue(ProvingUtxo.matchesScriptType("bc1qabc", null));
    }

    @Test
    public void theFirstQualifyingCoinIsChosen() {
        List<ProvingUtxo.Candidate> wallet = List.of(
                new ProvingUtxo.Candidate("bc1qsegwit", 500_000, 20),
                new ProvingUtxo.Candidate("bcrt1psmall", 1_000, 20),
                new ProvingUtxo.Candidate("bcrt1pgood", 300_000, 20));

        ProvingUtxo.Candidate chosen = ProvingUtxo.select(wallet, 100_000, 6, "p2tr");

        assertNotNull(chosen);
        assertEquals("bcrt1pgood", chosen.address());
    }

    @Test
    public void aWalletWithNoQualifyingCoinYieldsNothing() {
        assertNull(ProvingUtxo.select(List.of(
                new ProvingUtxo.Candidate("bc1qsegwit", 500_000, 20)), 100_000, 6, "p2tr"));
        assertNull(ProvingUtxo.select(List.of(), 100_000, 6, "p2tr"));
        assertNull(ProvingUtxo.select(null, 100_000, 6, "p2tr"));
    }
}
