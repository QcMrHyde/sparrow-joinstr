package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A peer's registration PSBT is combined straight into the transaction this client broadcasts, so
 * anything wrong with it is otherwise only discovered by the network, after the coinjoin has
 * already failed and the UTXOs have been tied up for the length of the pool.
 */
public class RegistrationPsbtTest {

    private static final long OUTPUT_AMOUNT = 99_900L;
    private static final long INPUT_VALUE = 100_500L;
    private static final int PEERS = 3;

    private ECKey key(int seed) {
        return ECKey.fromPrivate(BigInteger.valueOf(1000003L + seed));
    }

    private List<String> addresses(int count) {
        List<String> addresses = new ArrayList<>();
        for(int i = 0; i < count; i++) {
            addresses.add(ScriptType.P2WPKH.getAddress(key(i + 50)).toString());
        }
        return addresses;
    }

    /** Build a registration PSBT the way CoinjoinHandler builds its own. */
    private PSBT psbt(List<String> outputs, long outputAmount, long inputValue, SigHash sigHash,
            boolean withWitnessUtxo, boolean sign, int inputCount) throws Exception {
        Transaction tx = new Transaction();
        tx.setVersion(2);

        for(int i = 0; i < inputCount; i++) {
            tx.addInput(Sha256Hash.wrap(String.format("%064x", 0xabcdef00L + i)), i, new Script(new byte[0]));
        }

        List<String> sorted = new ArrayList<>(outputs);
        java.util.Collections.sort(sorted);
        for(String address : sorted) {
            tx.addOutput(outputAmount, Address.fromString(address).getOutputScript());
        }

        PSBT psbt = new PSBT(tx);
        ECKey signingKey = key(1);

        for(PSBTInput input : psbt.getPsbtInputs()) {
            if(sigHash != null) {
                input.setSigHash(sigHash);
            }
            if(withWitnessUtxo) {
                input.setWitnessUtxo(new TransactionOutput(tx, inputValue,
                        ScriptType.P2WPKH.getOutputScript(signingKey)));
            }
            if(sign) {
                assertTrue(input.sign(signingKey), "test setup: signing the input failed");
            }
        }

        return psbt;
    }

    private PSBT valid(List<String> outputs) throws Exception {
        return psbt(outputs, OUTPUT_AMOUNT, INPUT_VALUE, SigHash.ANYONECANPAY_ALL, true, true, 1);
    }

    private String check(PSBT psbt, List<String> registered) {
        return RegistrationPsbt.rejectionReason(psbt, registered, OUTPUT_AMOUNT, PEERS);
    }

    @Test
    public void aWellFormedRegistrationIsAccepted() throws Exception {
        List<String> outputs = addresses(PEERS);

        assertNull(check(valid(outputs), outputs));
    }

    @Test
    public void anUnsignedInputIsRejected() throws Exception {
        List<String> outputs = addresses(PEERS);
        PSBT unsigned = psbt(outputs, OUTPUT_AMOUNT, INPUT_VALUE, SigHash.ANYONECANPAY_ALL, true, false, 1);

        assertEquals("input is not signed", check(unsigned, outputs));
    }

    @Test
    public void aMissingWitnessUtxoIsRejected() throws Exception {
        List<String> outputs = addresses(PEERS);
        PSBT noUtxo = psbt(outputs, OUTPUT_AMOUNT, INPUT_VALUE, SigHash.ANYONECANPAY_ALL, false, false, 1);

        // without it the input value is unknown and the fee at finalization is computed from a
        // total that silently omits this peer's contribution
        assertEquals("input has no witness utxo", check(noUtxo, outputs));
    }

    @Test
    public void aNonPositiveInputValueIsRejected() throws Exception {
        List<String> outputs = addresses(PEERS);
        PSBT zeroValue = psbt(outputs, OUTPUT_AMOUNT, 0, SigHash.ANYONECANPAY_ALL, true, true, 1);

        assertNotNull(check(zeroValue, outputs));
        assertTrue(check(zeroValue, outputs).contains("not positive"), check(zeroValue, outputs));
    }

    @Test
    public void theWrongSighashIsRejected() throws Exception {
        List<String> outputs = addresses(PEERS);
        PSBT wrongSighash = psbt(outputs, OUTPUT_AMOUNT, INPUT_VALUE, SigHash.ALL, true, true, 1);

        String reason = check(wrongSighash, outputs);
        assertNotNull(reason);
        assertTrue(reason.contains("sighash"), reason);
    }

    @Test
    public void severalInputsInOneRegistrationAreRejected() throws Exception {
        List<String> outputs = addresses(PEERS);
        PSBT twoInputs = psbt(outputs, OUTPUT_AMOUNT, INPUT_VALUE, SigHash.ANYONECANPAY_ALL, true, true, 2);

        String reason = check(twoInputs, outputs);
        assertNotNull(reason);
        assertTrue(reason.contains("exactly one input"), reason);
    }

    @Test
    public void tooFewOutputsAreRejected() throws Exception {
        List<String> registered = addresses(PEERS);
        PSBT short2 = psbt(registered.subList(0, 2), OUTPUT_AMOUNT, INPUT_VALUE,
                SigHash.ANYONECANPAY_ALL, true, true, 1);

        String reason = check(short2, registered);
        assertNotNull(reason);
        assertTrue(reason.contains("outputs, expected 3"), reason);
    }

    /**
     * The hole in the old set based check. Every registered address is present, so the set of
     * addresses seen matches, but one peer is paid a second time. Only counting the outputs
     * catches it.
     */
    @Test
    public void anExtraPaymentToARegisteredPeerIsRejected() throws Exception {
        List<String> registered = addresses(PEERS);
        List<String> withExtra = new ArrayList<>(registered);
        withExtra.add(registered.get(0));

        PSBT psbt = psbt(withExtra, OUTPUT_AMOUNT, INPUT_VALUE, SigHash.ANYONECANPAY_ALL, true, true, 1);

        String reason = check(psbt, registered);
        assertNotNull(reason, "a transaction paying one peer twice was accepted");
        assertTrue(reason.contains("4 outputs, expected 3"), reason);

        // and the pool's own output validation refuses it too, rather than relying on the shape
        // check alone
        List<CoinjoinMath.OutputView> views = new ArrayList<>();
        for(TransactionOutput output : psbt.getTransaction().getOutputs()) {
            views.add(new CoinjoinMath.OutputView(output.getScript().getToAddress().toString(),
                    output.getValue()));
        }
        assertFalse(CoinjoinMath.validateOutputs(views, registered, OUTPUT_AMOUNT),
                "validateOutputs accepted an extra payment to an already registered peer");
    }

    @Test
    public void anUnregisteredOutputIsRejected() throws Exception {
        List<String> registered = addresses(PEERS);
        List<String> withStranger = new ArrayList<>(registered.subList(0, 2));
        withStranger.add(ScriptType.P2WPKH.getAddress(key(99)).toString());

        PSBT psbt = psbt(withStranger, OUTPUT_AMOUNT, INPUT_VALUE, SigHash.ANYONECANPAY_ALL, true, true, 1);

        assertEquals("psbt pays an output the pool did not register", check(psbt, registered));
    }

    @Test
    public void theWrongOutputAmountIsRejected() throws Exception {
        List<String> outputs = addresses(PEERS);
        PSBT shortChanged = psbt(outputs, OUTPUT_AMOUNT - 1000, INPUT_VALUE,
                SigHash.ANYONECANPAY_ALL, true, true, 1);

        String reason = check(shortChanged, outputs);
        assertNotNull(reason);
        assertTrue(reason.contains("expected " + OUTPUT_AMOUNT), reason);
    }

    @Test
    public void aMissingPsbtIsRejected() {
        assertNotNull(RegistrationPsbt.rejectionReason(null, addresses(PEERS), OUTPUT_AMOUNT, PEERS));
    }
}
