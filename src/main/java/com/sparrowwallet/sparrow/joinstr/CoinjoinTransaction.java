package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Last checks on the combined coinjoin before it is broadcast.
 *
 * Each peer's own registration was already checked as it arrived. These are the properties that
 * only exist once every registration is put together, so this is the only place they can be seen.
 */
public final class CoinjoinTransaction {

    /** Bounds on the total fee, per participant, outside regtest. */
    static final long MIN_FEE_PER_PEER = 100;
    static final long MAX_FEE_PER_PEER = 10000;

    private CoinjoinTransaction() {
    }

    /** Why the combined transaction must not be broadcast, or null if it may be. */
    public static String rejectionReason(PSBT combined, Collection<String> registeredOutputs,
            long expectedOutputAmount, int peers, boolean enforceFeeBounds) {
        if (combined == null) {
            return "no combined transaction";
        }

        List<String> inputAddresses = new ArrayList<>();
        long totalInput = 0;

        for (PSBTInput input : combined.getPsbtInputs()) {
            TransactionOutput witnessUtxo = input.getWitnessUtxo();
            if (witnessUtxo == null) {
                // the fee would otherwise be computed from a total that omits this input
                return "an input has no witness utxo, so the fee cannot be computed";
            }

            long value = witnessUtxo.getValue();
            if (value <= 0) {
                return "an input value is not positive: " + value;
            }
            totalInput += value;

            try {
                Address address = witnessUtxo.getScript().getToAddress();
                if (address != null) {
                    inputAddresses.add(address.toString());
                }
            } catch (Exception e) {
                // an input that does not resolve to an address cannot be compared, and the checks
                // below are about address reuse only
            }
        }

        Transaction tx = combined.extractTransaction();

        Set<String> outputAddresses = new HashSet<>();
        List<CoinjoinMath.OutputView> views = new ArrayList<>();
        for (TransactionOutput output : tx.getOutputs()) {
            try {
                String address = output.getScript().getToAddress().toString();
                outputAddresses.add(address);
                views.add(new CoinjoinMath.OutputView(address, output.getValue()));
            } catch (Exception e) {
                return "an output is not a payment to an address";
            }
        }

        if (!CoinjoinMath.validateOutputs(views, registeredOutputs, expectedOutputAmount)) {
            return "outputs do not match the addresses and amount the pool registered";
        }

        for (String inputAddress : inputAddresses) {
            if (outputAddresses.contains(inputAddress)) {
                // paying an input's own address defeats the coinjoin for whoever owns it
                return "an input address is reused as an output address";
            }
        }

        if (new HashSet<>(inputAddresses).size() != inputAddresses.size()) {
            // two inputs from one address are visibly the same owner, which shrinks the set
            return "two inputs spend from the same address";
        }

        long totalOutput = 0;
        for (TransactionOutput output : tx.getOutputs()) {
            totalOutput += output.getValue();
        }

        if (totalInput <= totalOutput) {
            return "inputs (" + totalInput + ") must exceed outputs (" + totalOutput + ")";
        }

        if (enforceFeeBounds) {
            long fee = totalInput - totalOutput;
            long participants = Math.max(peers, 1);
            if (fee < MIN_FEE_PER_PEER * participants || fee > MAX_FEE_PER_PEER * participants) {
                return "fee of " + fee + " sats is outside the expected range for "
                        + participants + " participants";
            }
        }

        return null;
    }

    public static long fee(PSBT combined) {
        long totalInput = 0;
        for (PSBTInput input : combined.getPsbtInputs()) {
            if (input.getWitnessUtxo() != null) {
                totalInput += input.getWitnessUtxo().getValue();
            }
        }

        long totalOutput = 0;
        for (TransactionOutput output : combined.extractTransaction().getOutputs()) {
            totalOutput += output.getValue();
        }

        return totalInput - totalOutput;
    }
}
