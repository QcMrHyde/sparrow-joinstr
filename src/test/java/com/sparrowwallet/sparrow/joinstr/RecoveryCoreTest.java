package com.sparrowwallet.sparrow.joinstr;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.protocol.TransactionWitness;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;

import org.bouncycastle.util.encoders.Base64;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pieces coinjoin recovery is built from: the ring taken from the phase 2 inputs, the
 * re-registration message, and the fee the rebuilt transaction pays.
 */
public class RecoveryCoreTest {

    private static final String POOL_ID = "0123456789abcdef";

    private ECKey key(int seed) {
        return ECKey.fromPrivate(BigInteger.valueOf(3000017L + seed));
    }

    private String address(int seed) {
        return ScriptType.P2WPKH.getAddress(key(seed + 400)).toString();
    }

    /** A signed single input registration, as a peer would publish it in phase 2. */
    private String registration(int keySeed, int outpointSeed, List<String> outputs) throws Exception {
        ECKey signingKey = key(keySeed);

        Transaction tx = new Transaction();
        tx.setVersion(2);
        tx.addInput(Sha256Hash.wrap(String.format("%064x", 0xfeed0000L + outpointSeed)),
                outpointSeed, new Script(new byte[0]));
        for(String output : outputs) {
            tx.addOutput(99_900L, Address.fromString(output).getOutputScript());
        }

        PSBT psbt = new PSBT(tx);
        PSBTInput input = psbt.getPsbtInputs().get(0);
        input.setSigHash(SigHash.ANYONECANPAY_ALL);
        input.setWitnessUtxo(new TransactionOutput(tx, 100_200L,
                ScriptType.P2WPKH.getOutputScript(signingKey)));
        assertTrue(input.sign(signingKey), "test setup: signing failed");

        var entry = input.getPartialSignatures().entrySet().iterator().next();
        input.setFinalScriptWitness(new TransactionWitness(tx, entry.getKey(), entry.getValue()));

        return Base64.toBase64String(psbt.serialize());
    }

    private List<String> outputs(int count) {
        List<String> addresses = new ArrayList<>();
        for(int i = 0; i < count; i++) {
            addresses.add(address(i));
        }
        return addresses;
    }

    // --- the ring ---

    @Test
    public void theRingIsTheKeysBehindTheRegisteredInputs() throws Exception {
        List<String> outs = outputs(3);
        List<String> registrations = List.of(
                registration(1, 1, outs), registration(2, 2, outs), registration(3, 3, outs));

        List<RingInput.Member> ring = RingInput.ring(registrations);

        assertEquals(3, ring.size());
        for(RingInput.Member member : ring) {
            assertEquals(66, member.pubKeyHex().length());
        }
    }

    /** Every peer has to build the same ring, so it is sorted rather than left in arrival order. */
    @Test
    public void theRingIsSortedSoEveryPeerBuildsTheSameOne() throws Exception {
        List<String> outs = outputs(3);
        String a = registration(1, 1, outs);
        String b = registration(2, 2, outs);
        String c = registration(3, 3, outs);

        List<String> first = RingInput.pubKeys(RingInput.ring(List.of(a, b, c)));
        List<String> second = RingInput.pubKeys(RingInput.ring(List.of(c, a, b)));

        assertEquals(first, second);
        assertEquals(first.stream().sorted().toList(), first);
    }

    /** A repeated key would inflate the ring, weakening every other member's cover. */
    @Test
    public void aRepeatedKeyIsNotAddedTwice() throws Exception {
        List<String> outs = outputs(3);
        List<RingInput.Member> ring = RingInput.ring(List.of(
                registration(1, 1, outs), registration(1, 2, outs), registration(2, 3, outs)));

        assertEquals(2, ring.size());
    }

    @Test
    public void aRepeatedOutpointIsNotAddedTwice() throws Exception {
        List<String> outs = outputs(3);
        List<RingInput.Member> ring = RingInput.ring(List.of(
                registration(1, 7, outs), registration(2, 7, outs)));

        assertEquals(1, ring.size());
    }

    /** The key comes from the witness, so it has to be bound to the input it claims. */
    @Test
    public void aKeyThatDoesNotMatchTheScriptPubKeyIsRejected() throws Exception {
        List<String> outs = outputs(3);
        PSBT psbt = new PSBT(Base64.decode(registration(1, 1, outs)), false);
        PSBTInput input = psbt.getPsbtInputs().get(0);

        // keep the signature but claim a different key owns the input
        input.setWitnessUtxo(new TransactionOutput(psbt.getTransaction(), 100_200L,
                ScriptType.P2WPKH.getOutputScript(key(99))));

        assertNull(RingInput.member(Base64.toBase64String(psbt.serialize())));
    }

    @Test
    public void anUnsignedOrMalformedRegistrationIsNotARingMember() {
        assertNull(RingInput.member("not base64"));
        assertNull(RingInput.member(""));
    }

    // --- re-registration ---

    private String signedReregister(int keySeed, String address, List<String> ring) {
        int index = ring.indexOf(com.sparrowwallet.drongo.Utils.bytesToHex(key(keySeed).getPubKey()));
        assertTrue(index >= 0, "test setup: signer is not in the ring");
        Lsag.Signature signature = Lsag.sign(Reregister.message(address, POOL_ID), ring,
                String.format("%064x", BigInteger.valueOf(3000017L + keySeed)), index);
        return Reregister.build(address, POOL_ID, signature);
    }

    @Test
    public void aValidReregisterIsAccepted() throws Exception {
        List<String> outs = outputs(3);
        List<String> ring = RingInput.pubKeys(RingInput.ring(List.of(
                registration(1, 1, outs), registration(2, 2, outs), registration(3, 3, outs))));

        String claimed = address(10);
        Reregister.Accepted accepted = Reregister.validate(signedReregister(2, claimed, ring),
                POOL_ID, ring, List.of(), Set.of());

        assertNotNull(accepted);
        assertEquals(claimed, accepted.address());
    }

    @Test
    public void aReregisterSignedByANonMemberIsRejected() throws Exception {
        List<String> outs = outputs(3);
        List<String> ring = RingInput.pubKeys(RingInput.ring(List.of(
                registration(1, 1, outs), registration(2, 2, outs), registration(3, 3, outs))));

        List<String> outsiderRing = new ArrayList<>(ring);
        outsiderRing.set(0, com.sparrowwallet.drongo.Utils.bytesToHex(key(77).getPubKey()));
        String claimed = address(11);
        Lsag.Signature outsider = Lsag.sign(Reregister.message(claimed, POOL_ID), outsiderRing,
                String.format("%064x", BigInteger.valueOf(3000017L + 77)), 0);

        assertNull(Reregister.validate(Reregister.build(claimed, POOL_ID, outsider),
                POOL_ID, ring, List.of(), Set.of()));
    }

    /** One key claims one output. The second attempt shares a key image and is refused. */
    @Test
    public void oneKeyCannotClaimTwoOutputs() throws Exception {
        List<String> outs = outputs(3);
        List<String> ring = RingInput.pubKeys(RingInput.ring(List.of(
                registration(1, 1, outs), registration(2, 2, outs), registration(3, 3, outs))));

        Reregister.Accepted first = Reregister.validate(signedReregister(2, address(10), ring),
                POOL_ID, ring, List.of(), Set.of());
        assertNotNull(first);

        Set<String> seen = new HashSet<>(Set.of(first.keyImage()));
        assertNull(Reregister.validate(signedReregister(2, address(11), ring),
                POOL_ID, ring, List.of(first.address()), seen));
    }

    /**
     * Dedup must use the key image inside the verified signature, not the field beside it, or a
     * member varies that field and claims an output for every peer.
     */
    @Test
    public void theKeyImageFieldCannotBeUsedToClaimASecondOutput() throws Exception {
        List<String> outs = outputs(3);
        List<String> ring = RingInput.pubKeys(RingInput.ring(List.of(
                registration(1, 1, outs), registration(2, 2, outs), registration(3, 3, outs))));

        Reregister.Accepted first = Reregister.validate(signedReregister(2, address(10), ring),
                POOL_ID, ring, List.of(), Set.of());
        assertNotNull(first);

        // same signer, second address, with the advertised key_image field altered
        JsonObject tampered = new Gson().fromJson(signedReregister(2, address(12), ring), JsonObject.class);
        tampered.addProperty("key_image", "02" + "11".repeat(32));

        assertNull(Reregister.validate(new Gson().toJson(tampered), POOL_ID, ring,
                List.of(first.address()), new HashSet<>(Set.of(first.keyImage()))));
    }

    @Test
    public void aReregisterForAnotherPoolIsRejected() throws Exception {
        List<String> outs = outputs(3);
        List<String> ring = RingInput.pubKeys(RingInput.ring(List.of(
                registration(1, 1, outs), registration(2, 2, outs), registration(3, 3, outs))));

        assertNull(Reregister.validate(signedReregister(2, address(10), ring),
                "a-different-pool", ring, List.of(), Set.of()));
    }

    @Test
    public void aReregisterWithAnInvalidAddressIsRejected() throws Exception {
        List<String> outs = outputs(3);
        List<String> ring = RingInput.pubKeys(RingInput.ring(List.of(
                registration(1, 1, outs), registration(2, 2, outs), registration(3, 3, outs))));

        JsonObject payload = new Gson().fromJson(signedReregister(2, address(10), ring), JsonObject.class);
        payload.addProperty("address", "not-an-address");

        assertNull(Reregister.validate(new Gson().toJson(payload), POOL_ID, ring, List.of(), Set.of()));
    }

    @Test
    public void otherMessageTypesAndGarbageAreIgnored() {
        assertNull(Reregister.validate("{\"type\":\"output\",\"address\":\"bc1q\"}",
                POOL_ID, List.of(), List.of(), Set.of()));
        assertNull(Reregister.validate("not json", POOL_ID, List.of(), List.of(), Set.of()));
        assertNull(Reregister.validate(null, POOL_ID, List.of(), List.of(), Set.of()));
    }

    // --- the rebuilt transaction's fee ---

    /**
     * Values computed from the reference implementation's own formula,
     * int(fee_rate * (100 * outputs + 68 * inputs + 100)) // outputs.
     */
    @Test
    public void theRecoveryFeeMatchesTheReferenceImplementation() {
        assertEquals(201L, CoinjoinMath.recoveryFeePerOutput(1, 3, 3));
        assertEquals(503L, CoinjoinMath.recoveryFeePerOutput(2.5, 3, 3));
        assertEquals(965L, CoinjoinMath.recoveryFeePerOutput(5, 4, 4));
        assertEquals(320L, CoinjoinMath.recoveryFeePerOutput(1, 2, 5));
        assertEquals(2369L, CoinjoinMath.recoveryFeePerOutput(13, 7, 7));
    }

    @Test
    public void theRecoveryOutputAmountMatchesTheReferenceImplementation() {
        assertEquals(99_799L, CoinjoinMath.recoveryOutputAmount(100_000L, 1, 3, 3));
        assertEquals(99_497L, CoinjoinMath.recoveryOutputAmount(100_000L, 2.5, 3, 3));
        assertEquals(97_631L, CoinjoinMath.recoveryOutputAmount(100_000L, 13, 7, 7));
    }

    /** Recovery pays for a real transaction, so it costs more than the phase 2 estimate. */
    @Test
    public void theRecoveryFeeIsLargerThanThePhaseTwoEstimate() {
        assertTrue(CoinjoinMath.recoveryFeePerOutput(1, 3, 3) > CoinjoinMath.feePerOutput(1, 3));
    }

    @Test
    public void theRecoveryFeeHandlesNoOutputs() {
        assertEquals(0L, CoinjoinMath.recoveryFeePerOutput(5, 0, 3));
    }
}
