package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;

import java.util.Collection;

/**
 * Checks on a registration PSBT received from a peer.
 *
 * A peer's PSBT is combined into the transaction this client broadcasts, so anything wrong with it
 * is only discovered by the network unless it is caught here. The outputs were already checked;
 * these cover the input side and the shape of the transaction.
 */
public final class RegistrationPsbt {

    private RegistrationPsbt() {
    }

    /** Why this PSBT cannot be accepted into the pool, or null if it can. */
    public static String rejectionReason(PSBT psbt, Collection<String> registeredOutputs,
            long expectedOutputAmount, int peers) {
        if (psbt == null) {
            return "psbt is missing";
        }

        if (psbt.getPsbtInputs().size() != 1) {
            // each peer registers exactly one input, so a PSBT carrying several is either a
            // different protocol or an attempt to take more than one slot
            return "psbt must register exactly one input, found " + psbt.getPsbtInputs().size();
        }

        PSBTInput input = psbt.getPsbtInputs().get(0);

        TransactionOutput witnessUtxo = input.getWitnessUtxo();
        if (witnessUtxo == null) {
            // without it the input value is unknown, and the fee cannot be computed at finalization
            return "input has no witness utxo";
        }

        if (witnessUtxo.getValue() <= 0) {
            return "input value is not positive: " + witnessUtxo.getValue();
        }

        if (!input.isSigned() && !input.isFinalized()) {
            return "input is not signed";
        }

        SigHash sigHash = input.getSigHash();
        if (sigHash != null && sigHash != CoinjoinMath.INPUT_SIGHASH) {
            // a signature over anything other than all outputs plus this input does not commit to
            // the coinjoin the pool agreed
            return "input sighash is " + sigHash + ", expected " + CoinjoinMath.INPUT_SIGHASH;
        }

        Transaction tx = psbt.getTransaction();
        if (tx.getOutputs().size() != peers) {
            return "psbt has " + tx.getOutputs().size() + " outputs, expected " + peers;
        }

        for (TransactionOutput output : tx.getOutputs()) {
            Address address;
            try {
                address = output.getScript().getToAddress();
            } catch (Exception e) {
                return "psbt has an output that is not a payment to an address";
            }

            if (address == null || !registeredOutputs.contains(address.toString())) {
                return "psbt pays an output the pool did not register";
            }

            if (output.getValue() != expectedOutputAmount) {
                return "psbt has an output of " + output.getValue() + " sats, expected "
                        + expectedOutputAmount;
            }
        }

        return null;
    }
}
