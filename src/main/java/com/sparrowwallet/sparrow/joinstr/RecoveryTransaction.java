package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;

import org.bouncycastle.util.encoders.Base64;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The transaction coinjoin recovery rebuilds.
 *
 * Every peer has to construct this identically from the confirmed phase 2 inputs and the
 * re-registered outputs, or their signatures do not agree. Unlike a registration PSBT it is signed
 * with SIGHASH_ALL, so nothing can be added to it afterwards.
 */
public final class RecoveryTransaction {

    private RecoveryTransaction() {
    }

    /** Inputs go in outpoint order so every peer builds the same transaction. */
    static List<RingInput.Member> orderedInputs(List<RingInput.Member> ring) {
        List<RingInput.Member> ordered = new ArrayList<>(ring);
        ordered.sort(Comparator.comparing(RingInput.Member::prevout));
        return ordered;
    }

    /** Why this transaction must not be signed, or null if it may be. */
    public static String rejectionReason(List<String> outputs, long outputAmount,
            List<RingInput.Member> ring) {
        if (outputs == null || outputs.isEmpty() || ring == null || ring.isEmpty()) {
            return "recovery needs at least one input and one output";
        }

        if (outputAmount <= 0) {
            return "output amount is not positive: " + outputAmount;
        }

        long dust = 0;
        for (String address : outputs) {
            try {
                dust = Math.max(dust, com.sparrowwallet.sparrow.wallet.PaymentController
                        .getRecipientDustThreshold(Address.fromString(address)));
            } catch (Exception e) {
                return "an output is not a valid address";
            }
        }

        if (outputAmount < dust) {
            return "output amount " + outputAmount + " is below the dust limit " + dust;
        }

        long totalIn = 0;
        for (RingInput.Member member : ring) {
            long value = inputValue(member);
            if (value <= 0) {
                return "an input value is missing or not positive";
            }
            totalIn += value;
        }

        long totalOut = outputAmount * outputs.size();
        if (totalIn <= totalOut) {
            return "inputs (" + totalIn + ") must exceed outputs (" + totalOut + ")";
        }

        long fee = totalIn - totalOut;
        if (fee < 100L * ring.size() || fee > 10000L * ring.size()) {
            return "fee of " + fee + " sats is outside the expected range for " + ring.size()
                    + " inputs";
        }

        return null;
    }

    /** Build the unsigned recovery transaction. */
    public static PSBT build(List<String> outputs, long outputAmount, List<RingInput.Member> ring) {
        try {
            Transaction tx = new Transaction();
            tx.setVersion(2);

            List<RingInput.Member> inputs = orderedInputs(ring);
            for (RingInput.Member member : inputs) {
                String[] parts = member.prevout().split(":");
                tx.addInput(Sha256Hash.wrap(parts[0]), Integer.parseInt(parts[1]), new Script(new byte[0]));
            }

            for (String address : outputs) {
                tx.addOutput(outputAmount, Address.fromString(address).getOutputScript());
            }

            PSBT psbt = new PSBT(tx);
            for (int i = 0; i < inputs.size(); i++) {
                TransactionOutput witnessUtxo = witnessUtxo(inputs.get(i));
                if (witnessUtxo != null) {
                    psbt.getPsbtInputs().get(i).setWitnessUtxo(witnessUtxo);
                }
            }

            return psbt;
        } catch (Exception e) {
            return null;
        }
    }

    /** Merge the peers' re-signed transactions, or null if they do not agree. */
    public static Transaction combine(List<String> signedPsbts) {
        try {
            if (signedPsbts.isEmpty()) {
                return null;
            }

            PSBT combined = new PSBT(Base64.decode(signedPsbts.get(0)), false);

            for (int i = 1; i < signedPsbts.size(); i++) {
                PSBT other = new PSBT(Base64.decode(signedPsbts.get(i)), false);

                if (!sameTransaction(combined, other)) {
                    // a peer that re-signed a different transaction cannot be merged into this one
                    return null;
                }

                for (int input = 0; input < combined.getPsbtInputs().size(); input++) {
                    PSBTInput target = combined.getPsbtInputs().get(input);
                    PSBTInput source = other.getPsbtInputs().get(input);

                    if (target.getFinalScriptWitness() == null && source.getFinalScriptWitness() != null) {
                        target.setFinalScriptWitness(source.getFinalScriptWitness());
                    }
                    if (target.getWitnessUtxo() == null && source.getWitnessUtxo() != null) {
                        target.setWitnessUtxo(source.getWitnessUtxo());
                    }
                }
            }

            for (PSBTInput input : combined.getPsbtInputs()) {
                if (input.getFinalScriptWitness() == null) {
                    return null;
                }
            }

            return combined.extractTransaction();
        } catch (Exception e) {
            return null;
        }
    }

    public static long fee(int outputCount, long outputAmount, List<RingInput.Member> ring) {
        long totalIn = 0;
        for (RingInput.Member member : ring) {
            totalIn += inputValue(member);
        }
        return totalIn - outputAmount * outputCount;
    }

    /** Whether two PSBTs describe the same transaction, signatures aside. */
    static boolean sameTransaction(PSBT a, PSBT b) {
        try {
            Transaction first = a.getTransaction();
            Transaction second = b.getTransaction();

            if (first.getInputs().size() != second.getInputs().size()
                    || first.getOutputs().size() != second.getOutputs().size()) {
                return false;
            }

            for (int i = 0; i < first.getInputs().size(); i++) {
                if (!first.getInputs().get(i).getOutpoint().toString()
                        .equals(second.getInputs().get(i).getOutpoint().toString())) {
                    return false;
                }
            }

            for (int i = 0; i < first.getOutputs().size(); i++) {
                TransactionOutput out = first.getOutputs().get(i);
                TransactionOutput other = second.getOutputs().get(i);
                if (out.getValue() != other.getValue()
                        || !out.getScript().getToAddress().toString()
                                .equals(other.getScript().getToAddress().toString())) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static TransactionOutput witnessUtxo(RingInput.Member member) {
        try {
            PSBT psbt = new PSBT(Base64.decode(member.psbtBase64()), false);
            return psbt.getPsbtInputs().get(0).getWitnessUtxo();
        } catch (Exception e) {
            return null;
        }
    }

    private static long inputValue(RingInput.Member member) {
        TransactionOutput witnessUtxo = witnessUtxo(member);
        return witnessUtxo == null ? 0 : witnessUtxo.getValue();
    }
}
