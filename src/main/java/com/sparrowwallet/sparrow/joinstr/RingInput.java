package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.protocol.TransactionWitness;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ring used by coinjoin recovery: the public keys behind the inputs registered in phase 2.
 *
 * Registration PSBTs carry no derivation paths, by design, so the key has to come from the
 * witness. Taking it from there alone would let a peer name any key it likes, so it is bound to
 * the input by checking the scriptPubKey is the hash of that key.
 */
public final class RingInput {

    /** One ring member: the key that signed an input, and the input it signed. */
    public record Member(String pubKeyHex, String prevout, String psbtBase64) {
    }

    private RingInput() {
    }

    /**
     * The key and outpoint behind a registration PSBT, or null if it does not qualify.
     * Only P2WPKH inputs are accepted, matching the reference implementation.
     */
    public static Member member(String psbtBase64) {
        try {
            PSBT psbt = new PSBT(org.bouncycastle.util.encoders.Base64.decode(psbtBase64), false);
            if (psbt.getPsbtInputs().size() != 1) {
                return null;
            }

            PSBTInput input = psbt.getPsbtInputs().get(0);
            TransactionOutput witnessUtxo = input.getWitnessUtxo();
            if (witnessUtxo == null) {
                return null;
            }

            byte[] scriptPubKey = witnessUtxo.getScript().getProgram();
            // P2WPKH scriptPubKey is OP_0 PUSH20 <hash160(pubkey)>
            if (scriptPubKey == null || scriptPubKey.length != 22
                    || scriptPubKey[0] != 0x00 || scriptPubKey[1] != 0x14) {
                return null;
            }

            TransactionWitness witness = input.getFinalScriptWitness();
            if (witness == null || witness.getPushes().isEmpty()) {
                return null;
            }

            List<byte[]> pushes = witness.getPushes();
            byte[] pubKey = pushes.get(pushes.size() - 1);
            byte[] expected = Arrays.copyOfRange(scriptPubKey, 2, 22);
            if (!Arrays.equals(Utils.sha256hash160(pubKey), expected)) {
                return null;
            }

            String prevout = input.getInput().getOutpoint().getHash().toString() + ":"
                    + input.getInput().getOutpoint().getIndex();

            return new Member(Utils.bytesToHex(pubKey), prevout, psbtBase64);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The ring for a set of phase 2 registrations, sorted so every peer builds the same one.
     *
     * A registration that does not qualify is dropped, and so is one that repeats a key or an
     * outpoint already in the ring: without that a peer could inflate the ring, which weakens
     * every other member's anonymity, or displace another member's input.
     */
    public static List<Member> ring(List<String> registrations) {
        Map<String, Member> byKey = new LinkedHashMap<>();
        Set<String> seenPrevouts = new HashSet<>();

        for (String registration : registrations) {
            Member member = member(registration);
            if (member == null) {
                continue;
            }
            if (byKey.containsKey(member.pubKeyHex()) || seenPrevouts.contains(member.prevout())) {
                continue;
            }
            seenPrevouts.add(member.prevout());
            byKey.put(member.pubKeyHex(), member);
        }

        List<Member> ring = new ArrayList<>(byKey.values());
        ring.sort((a, b) -> a.pubKeyHex().compareTo(b.pubKeyHex()));
        return ring;
    }

    public static List<String> pubKeys(List<Member> ring) {
        List<String> pubKeys = new ArrayList<>(ring.size());
        for (Member member : ring) {
            pubKeys.add(member.pubKeyHex());
        }
        return pubKeys;
    }
}
