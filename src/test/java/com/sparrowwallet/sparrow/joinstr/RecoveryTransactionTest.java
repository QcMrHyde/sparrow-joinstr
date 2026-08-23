package com.sparrowwallet.sparrow.joinstr;

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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The transaction recovery rebuilds. Every peer constructs it from the same confirmed inputs and
 * re-registered outputs, so it has to come out identical for their signatures to agree.
 */
public class RecoveryTransactionTest {

    private static final long INPUT_VALUE = 100_500L;

    private ECKey key(int seed) {
        return ECKey.fromPrivate(BigInteger.valueOf(4000037L + seed));
    }

    private String address(int seed) {
        return ScriptType.P2WPKH.getAddress(key(seed + 700)).toString();
    }

    private RingInput.Member member(int keySeed, int outpointSeed) throws Exception {
        Transaction tx = new Transaction();
        tx.setVersion(2);
        tx.addInput(Sha256Hash.wrap(String.format("%064x", 0xaa0000L + outpointSeed)),
                outpointSeed, new Script(new byte[0]));
        tx.addOutput(99_900L, Address.fromString(address(0)).getOutputScript());

        PSBT psbt = new PSBT(tx);
        PSBTInput input = psbt.getPsbtInputs().get(0);
        input.setSigHash(SigHash.ANYONECANPAY_ALL);
        input.setWitnessUtxo(new TransactionOutput(tx, INPUT_VALUE,
                ScriptType.P2WPKH.getOutputScript(key(keySeed))));
        assertTrue(input.sign(key(keySeed)));
        var entry = input.getPartialSignatures().entrySet().iterator().next();
        input.setFinalScriptWitness(new TransactionWitness(tx, entry.getKey(), entry.getValue()));

        return RingInput.member(Base64.toBase64String(psbt.serialize()));
    }

    private List<RingInput.Member> ring(int size) throws Exception {
        List<RingInput.Member> ring = new ArrayList<>();
        for(int i = 0; i < size; i++) {
            ring.add(member(i + 1, i + 1));
        }
        return ring;
    }

    private List<String> outputs(int count) {
        List<String> outputs = new ArrayList<>();
        for(int i = 0; i < count; i++) {
            outputs.add(address(i));
        }
        Collections.sort(outputs);
        return outputs;
    }

    /** Sign every input of a built recovery transaction, as all the peers together would. */
    private String fullySigned(PSBT psbt) {
        for(PSBTInput input : psbt.getPsbtInputs()) {
            // member(i, i) was built with key seed == outpoint index
            int seed = (int) input.getInput().getOutpoint().getIndex();
            input.setSigHash(SigHash.ALL);
            assertTrue(input.sign(key(seed)), "test setup: could not sign a recovery input");
            var entry = input.getPartialSignatures().entrySet().iterator().next();
            input.setFinalScriptWitness(
                    new TransactionWitness(psbt.getTransaction(), entry.getKey(), entry.getValue()));
        }
        return Base64.toBase64String(psbt.serialize());
    }

    @Test
    public void inputsGoInOutpointOrderWhateverOrderTheRingIsIn() throws Exception {
        List<RingInput.Member> ring = ring(3);
        List<RingInput.Member> shuffled = new ArrayList<>(ring);
        Collections.reverse(shuffled);

        assertEquals(RecoveryTransaction.orderedInputs(ring),
                RecoveryTransaction.orderedInputs(shuffled));
    }

    /** Two peers must build byte identical transactions or their signatures cannot combine. */
    @Test
    public void twoPeersBuildTheSameTransaction() throws Exception {
        List<RingInput.Member> ring = ring(3);
        List<String> outputs = outputs(3);
        long amount = CoinjoinMath.recoveryOutputAmount(100_000L, 1, 3, 3);

        List<RingInput.Member> reversed = new ArrayList<>(ring);
        Collections.reverse(reversed);

        PSBT first = RecoveryTransaction.build(outputs, amount, ring);
        PSBT second = RecoveryTransaction.build(outputs, amount, reversed);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.getTransaction().getTxId(), second.getTransaction().getTxId());
    }

    @Test
    public void theBuiltTransactionCarriesEveryInputAndOutput() throws Exception {
        List<RingInput.Member> ring = ring(3);
        List<String> outputs = outputs(3);
        long amount = CoinjoinMath.recoveryOutputAmount(100_000L, 1, 3, 3);

        PSBT psbt = RecoveryTransaction.build(outputs, amount, ring);

        assertEquals(3, psbt.getTransaction().getInputs().size());
        assertEquals(3, psbt.getTransaction().getOutputs().size());
        for(PSBTInput input : psbt.getPsbtInputs()) {
            assertNotNull(input.getWitnessUtxo(), "an input lost its value");
        }
    }

    @Test
    public void aWellFormedRecoveryIsAccepted() throws Exception {
        List<RingInput.Member> ring = ring(3);
        long amount = CoinjoinMath.recoveryOutputAmount(100_000L, 1, 3, 3);

        assertNull(RecoveryTransaction.rejectionReason(outputs(3), amount, ring));
    }

    @Test
    public void outputsExceedingInputsAreRejected() throws Exception {
        List<RingInput.Member> ring = ring(3);

        String reason = RecoveryTransaction.rejectionReason(outputs(3), INPUT_VALUE + 1, ring);

        assertNotNull(reason);
        assertTrue(reason.contains("must exceed outputs"), reason);
    }

    @Test
    public void anAbsurdFeeIsRejected() throws Exception {
        List<RingInput.Member> ring = ring(3);

        // 3 inputs of 100500 against 3 outputs of 50000 leaves a fee of 151500
        String reason = RecoveryTransaction.rejectionReason(outputs(3), 50_000L, ring);

        assertNotNull(reason);
        assertTrue(reason.contains("outside the expected range"), reason);
    }

    @Test
    public void aDustOutputIsRejected() throws Exception {
        List<RingInput.Member> ring = ring(3);

        String reason = RecoveryTransaction.rejectionReason(outputs(3), 100L, ring);

        assertNotNull(reason);
        assertTrue(reason.contains("dust") || reason.contains("expected range"), reason);
    }

    @Test
    public void emptyInputsOrOutputsAreRejected() throws Exception {
        assertNotNull(RecoveryTransaction.rejectionReason(List.of(), 99_000L, ring(3)));
        assertNotNull(RecoveryTransaction.rejectionReason(outputs(3), 99_000L, List.of()));
        assertNotNull(RecoveryTransaction.rejectionReason(outputs(3), 0, ring(3)));
    }

    /** The happy path, so the rejection below cannot pass just because nothing ever combines. */
    @Test
    public void fullySignedPeersCombineIntoATransaction() throws Exception {
        List<RingInput.Member> ring = ring(3);
        long amount = CoinjoinMath.recoveryOutputAmount(100_000L, 1, 3, 3);

        String signed = fullySigned(RecoveryTransaction.build(outputs(3), amount, ring));

        Transaction combined = RecoveryTransaction.combine(List.of(signed, signed));

        assertNotNull(combined, "signed peers should combine");
        assertEquals(3, combined.getInputs().size());
        assertEquals(3, combined.getOutputs().size());
    }

    /**
     * Both sides are fully signed here, so only the transaction check can reject this. An
     * earlier version of this test used unsigned PSBTs, which the witness check rejected anyway
     * and so proved nothing.
     */
    @Test
    public void psbtsForDifferentTransactionsAreNotCombined() throws Exception {
        List<RingInput.Member> ring = ring(3);
        long amount = CoinjoinMath.recoveryOutputAmount(100_000L, 1, 3, 3);

        String mine = fullySigned(RecoveryTransaction.build(outputs(3), amount, ring));
        String different = fullySigned(RecoveryTransaction.build(outputs(3), amount - 500, ring));

        assertNull(RecoveryTransaction.combine(List.of(mine, different)),
                "a peer re-signing a different transaction was merged in");
    }

    @Test
    public void anUnsignedCombinationIsRefused() throws Exception {
        List<RingInput.Member> ring = ring(3);
        long amount = CoinjoinMath.recoveryOutputAmount(100_000L, 1, 3, 3);
        PSBT unsigned = RecoveryTransaction.build(outputs(3), amount, ring);

        assertNull(RecoveryTransaction.combine(List.of(Base64.toBase64String(unsigned.serialize()))),
                "an unsigned transaction was treated as complete");
    }

    @Test
    public void theSameTransactionCheckSpotsAChangedOutput() throws Exception {
        List<RingInput.Member> ring = ring(3);
        long amount = CoinjoinMath.recoveryOutputAmount(100_000L, 1, 3, 3);

        PSBT a = RecoveryTransaction.build(outputs(3), amount, ring);
        PSBT b = RecoveryTransaction.build(outputs(3), amount, ring);
        PSBT c = RecoveryTransaction.build(outputs(3), amount - 1, ring);

        assertTrue(RecoveryTransaction.sameTransaction(a, b));
        assertFalse(RecoveryTransaction.sameTransaction(a, c));
    }
}
