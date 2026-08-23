package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.sparrow.wallet.NodeEntry;
import com.sparrowwallet.sparrow.wallet.WalletForm;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Picks the output address a pool pays to.
 *
 * A wallet hands out the first address it has not seen used, so two pools in a row get the same
 * one whenever the first pool did not complete. Two coinjoins paying the same address is the
 * reuse a coinjoin is meant to avoid, so an address handed to one pool is not offered again.
 */
public final class OutputAddress {

    /** How far to look before giving up and taking whatever the wallet offers. */
    private static final int MAX_LOOKAHEAD = 100;

    private static final Set<String> reserved = ConcurrentHashMap.newKeySet();

    private OutputAddress() {
    }

    /** The first candidate not already promised to a pool, reserving it. */
    static String reserveFirstFree(Supplier<String> candidates) {
        String last = null;
        for (int i = 0; i < MAX_LOOKAHEAD; i++) {
            String candidate = candidates.get();
            if (candidate == null || candidate.equals(last)) {
                // the wallet has stopped producing new addresses
                break;
            }
            last = candidate;
            if (reserved.add(candidate)) {
                return candidate;
            }
        }
        return last;
    }

    /** A receive address this session has not already given to a pool. */
    public static Address fresh(WalletForm walletForm) {
        NodeEntry[] cursor = new NodeEntry[1];

        String address = reserveFirstFree(() -> {
            cursor[0] = walletForm.getFreshNodeEntry(KeyPurpose.RECEIVE, cursor[0]);
            return cursor[0] == null || cursor[0].getAddress() == null
                    ? null
                    : cursor[0].getAddress().toString();
        });

        return address == null || cursor[0] == null ? null : cursor[0].getAddress();
    }

    static void resetForTesting() {
        reserved.clear();
    }
}
