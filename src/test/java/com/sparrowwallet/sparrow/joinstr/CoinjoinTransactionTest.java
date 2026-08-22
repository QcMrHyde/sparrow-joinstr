package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Address reuse and a wrong fee only become visible once every peer's registration has been
 * combined, so this is the last point at which the transaction can be stopped. After this it is
 * on the network and on chain.
 */
public class CoinjoinTransactionTest {

    private static final int PEERS = 3;
    private static final long OUTPUT_AMOUNT = 99_900L;
    private static final long INPUT_VALUE = 100_200L;

    private ECKey key(int seed) {
        return ECKey.fromPrivate(BigInteger.valueOf(2000003L + seed));
    }

    private String address(int seed) {
        return ScriptType.P2WPKH.getAddress(key(seed)).toString();
    }

    private List<String> outputs(int count) {
        List<String> addresses = new ArrayList<>();
        for(int i = 0; i < count; i++) {
            addresses.add(address(i + 50));
        }
        return addresses;
    }

    /**
     * Build the combined transaction the way finalizeCoinjoin does: one input per peer, each
     * carrying its witness utxo, and the agreed output set.
     */
    private PSBT combined(List<String> outputAddresses, List<Integer> inputKeySeeds, long inputValue,
            long outputAmount) throws Exception {
        Transaction tx = new Transaction();
        tx.setVersion(2);

        for(int i = 0; i < inputKeySeeds.size(); i++) {
            tx.addInput(Sha256Hash.wrap(String.format("%064x", 0x1234500L + i)), i, new Script(new byte[0]));
        }

        List<String> sorted = new ArrayList<>(outputAddresses);
        Collections.sort(sorted);
        for(String address : sorted) {
            tx.addOutput(outputAmount, Address.fromString(address).getOutputScript());
        }

        PSBT psbt = new PSBT(tx);
        for(int i = 0; i < inputKeySeeds.size(); i++) {
            PSBTInput input = psbt.getPsbtInputs().get(i);
            input.setWitnessUtxo(new TransactionOutput(tx, inputValue,
                    ScriptType.P2WPKH.getOutputScript(key(inputKeySeeds.get(i)))));
        }
        return psbt;
    }

    private List<Integer> distinctInputs() {
        return List.of(1, 2, 3);
    }

    private String check(PSBT psbt, List<String> registered) {
        return CoinjoinTransaction.rejectionReason(psbt, registered, OUTPUT_AMOUNT, PEERS, true);
    }

    @Test
    public void aWellFormedCoinjoinIsAccepted() throws Exception {
        List<String> registered = outputs(PEERS);

        assertNull(check(combined(registered, distinctInputs(), INPUT_VALUE, OUTPUT_AMOUNT), registered));
    }

    /** An input paying its own address back out defeats the coinjoin for whoever owns it. */
    @Test
    public void anInputAddressReusedAsAnOutputIsRejected() throws Exception {
        List<String> registered = new ArrayList<>(outputs(PEERS - 1));
        registered.add(address(1)); // the address input 1 spends from

        PSBT psbt = combined(registered, distinctInputs(), INPUT_VALUE, OUTPUT_AMOUNT);

        assertEquals("an input address is reused as an output address", check(psbt, registered));
    }

    @Test
    public void twoInputsFromTheSameAddressAreRejected() throws Exception {
        List<String> registered = outputs(PEERS);
        PSBT psbt = combined(registered, List.of(1, 1, 3), INPUT_VALUE, OUTPUT_AMOUNT);

        assertEquals("two inputs spend from the same address", check(psbt, registered));
    }

    @Test
    public void aMissingWitnessUtxoIsRejected() throws Exception {
        List<String> registered = outputs(PEERS);
        PSBT psbt = combined(registered, distinctInputs(), INPUT_VALUE, OUTPUT_AMOUNT);
        psbt.getPsbtInputs().get(1).setWitnessUtxo(null);

        String reason = check(psbt, registered);
        assertNotNull(reason);
        assertTrue(reason.contains("no witness utxo"), reason);
    }

    @Test
    public void outputsExceedingInputsAreRejected() throws Exception {
        List<String> registered = outputs(PEERS);
        // each peer contributes less than it takes out
        PSBT psbt = combined(registered, distinctInputs(), OUTPUT_AMOUNT - 100, OUTPUT_AMOUNT);

        String reason = check(psbt, registered);
        assertNotNull(reason);
        assertTrue(reason.contains("must exceed outputs"), reason);
    }

    @Test
    public void anAbsurdlyHighFeeIsRejected() throws Exception {
        List<String> registered = outputs(PEERS);
        // 3 inputs of 200_000 against 3 outputs of 99_900 leaves a fee of 300_300 sats
        PSBT psbt = combined(registered, distinctInputs(), 200_000L, OUTPUT_AMOUNT);

        String reason = check(psbt, registered);
        assertNotNull(reason);
        assertTrue(reason.contains("outside the expected range"), reason);
    }

    @Test
    public void aFeeBelowTheFloorIsRejected() throws Exception {
        List<String> registered = outputs(PEERS);
        // 3 sats of total fee across 3 participants will not relay
        PSBT psbt = combined(registered, distinctInputs(), OUTPUT_AMOUNT + 1, OUTPUT_AMOUNT);

        String reason = check(psbt, registered);
        assertNotNull(reason);
        assertTrue(reason.contains("outside the expected range"), reason);
    }

    @Test
    public void theFeeBoundsCanBeDisabledForRegtest() throws Exception {
        List<String> registered = outputs(PEERS);
        PSBT psbt = combined(registered, distinctInputs(), 200_000L, OUTPUT_AMOUNT);

        assertNotNull(CoinjoinTransaction.rejectionReason(psbt, registered, OUTPUT_AMOUNT, PEERS, true));
        assertNull(CoinjoinTransaction.rejectionReason(psbt, registered, OUTPUT_AMOUNT, PEERS, false));
    }

    @Test
    public void anUnregisteredOutputIsRejected() throws Exception {
        List<String> registered = outputs(PEERS);
        List<String> paid = new ArrayList<>(registered.subList(0, PEERS - 1));
        paid.add(address(99));

        PSBT psbt = combined(paid, distinctInputs(), INPUT_VALUE, OUTPUT_AMOUNT);

        String reason = check(psbt, registered);
        assertNotNull(reason);
        assertTrue(reason.contains("do not match"), reason);
    }

    @Test
    public void theReportedFeeIsInputsMinusOutputs() throws Exception {
        List<String> registered = outputs(PEERS);
        PSBT psbt = combined(registered, distinctInputs(), INPUT_VALUE, OUTPUT_AMOUNT);

        assertEquals(PEERS * (INPUT_VALUE - OUTPUT_AMOUNT), CoinjoinTransaction.fee(psbt));
    }

    @Test
    public void aMissingTransactionIsRejected() {
        assertNotNull(CoinjoinTransaction.rejectionReason(null, outputs(PEERS), OUTPUT_AMOUNT, PEERS, true));
    }
}
