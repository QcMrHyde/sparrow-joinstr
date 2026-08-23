package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Finds the key an aut-ct proof is made with.
 *
 * Two ways in, because neither covers everyone. A Taproot wallet can prove with one of its own
 * coins and never show a private key. A wallet with no Taproot coin, or whose coins are not in
 * the pool's keyset, has to be given a key from elsewhere, which is what the reference
 * implementation does for every pool because Electrum cannot export single sig Taproot keys.
 */
public final class ProvingKey {

    private static final Logger logger = Logger.getLogger(ProvingKey.class.getName());

    private ProvingKey() {
    }

    /** The candidates a wallet offers, judged on amount, confirmations and script type. */
    public static List<ProvingUtxo.Candidate> candidates(Wallet wallet, int currentHeight) {
        List<ProvingUtxo.Candidate> candidates = new ArrayList<>();
        if (wallet == null) {
            return candidates;
        }

        try {
            for (Map.Entry<BlockTransactionHashIndex, WalletNode> entry
                    : wallet.getSpendableUtxos().entrySet()) {
                BlockTransactionHashIndex utxo = entry.getKey();
                if (utxo.getHeight() <= 0) {
                    continue;
                }

                String address = null;
                try {
                    address = entry.getValue().getAddress().toString();
                } catch (Exception e) {
                    continue;
                }

                int confirmations = currentHeight <= 0 ? 1 : (int) (currentHeight - utxo.getHeight() + 1);
                candidates.add(new ProvingUtxo.Candidate(address, utxo.getValue(), confirmations));
            }
        } catch (Exception e) {
            logger.warning("Could not read the wallet's coins for an aut-ct proof: " + e.getMessage());
        }

        return candidates;
    }

    /**
     * A WIF from the wallet for a coin that meets the requirement, or null when it has none.
     *
     * Only a Taproot wallet can answer, since the keyset holds Taproot output keys.
     */
    public static String fromWallet(Wallet wallet, int currentHeight, long minAmount,
            int minConfirmations, String scriptType) {
        if (wallet == null || wallet.getScriptType() != ScriptType.P2TR) {
            return null;
        }

        try {
            for (Map.Entry<BlockTransactionHashIndex, WalletNode> entry
                    : wallet.getSpendableUtxos().entrySet()) {
                BlockTransactionHashIndex utxo = entry.getKey();
                WalletNode node = entry.getValue();
                if (utxo.getHeight() <= 0) {
                    continue;
                }

                String address;
                try {
                    address = node.getAddress().toString();
                } catch (Exception e) {
                    continue;
                }

                int confirmations = currentHeight <= 0 ? 1 : (int) (currentHeight - utxo.getHeight() + 1);
                ProvingUtxo.Candidate candidate =
                        new ProvingUtxo.Candidate(address, utxo.getValue(), confirmations);
                if (!ProvingUtxo.qualifies(candidate, minAmount, minConfirmations, scriptType)) {
                    continue;
                }

                Keystore keystore = wallet.getKeystores().get(0);
                ECKey key = keystore.getKey(node);
                if (key == null || !key.hasPrivKey()) {
                    continue;
                }

                return key.getPrivateKeyEncoded().toBase58();
            }
        } catch (Exception e) {
            logger.severe("Could not export a proving key from the wallet: " + e.getMessage());
        }

        return null;
    }

    /** Whether a string looks like a WIF this client can hand to aut-ct. */
    public static boolean looksLikeWif(String wif) {
        if (wif == null) {
            return false;
        }
        String trimmed = wif.trim();
        return trimmed.length() >= 50 && trimmed.length() <= 53
                && trimmed.matches("[1-9A-HJ-NP-Za-km-z]+");
    }
}
