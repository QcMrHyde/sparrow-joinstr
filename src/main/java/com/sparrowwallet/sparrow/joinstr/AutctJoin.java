package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/** Joining a pool that demands an aut-ct proof. */
public final class AutctJoin {

    private static final Logger logger = Logger.getLogger(AutctJoin.class.getName());

    public static final String NO_PROOF =
            "Could not generate the aut-ct proof this pool requires.\n\n"
                    + "Check that an aut-ct server is running and that the key you gave owns a "
                    + "Taproot output in the pool's keyset.";

    /** Returned when the user wants the wallet to pick a coin rather than typing a key. */
    static final String USE_WALLET = "";

    private AutctJoin() {
    }

    /**
     * Ask for the Taproot key to prove with.
     *
     * Blank means use a coin from the wallet, which only a Taproot wallet can do. Returns null if
     * the user cancels.
     */
    public static String askForProvingKey(JoinstrPool pool) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("aut-ct Proving Key");
        DialogPane pane = dialog.getDialogPane();
        AppServices.setStageIcon(pane.getScene().getWindow());
        pane.getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        Label explain = new Label("This pool requires an aut-ct proof"
                + (pool.getRequirement() == null ? "" : " (" + pool.getRequirement() + ")") + ".\n\n"
                + "Enter the WIF of a Taproot key whose output is in the keyset, or leave it blank "
                + "to prove with a Taproot coin from this wallet.");
        explain.setWrapText(true);

        PasswordField wif = new PasswordField();
        wif.setPromptText("Taproot WIF, or blank to use this wallet");

        VBox content = new VBox(10, explain, wif);
        content.setPrefWidth(460);
        pane.setContent(content);

        // the converter has to be in place before the dialog is shown
        dialog.setResultConverter(button -> button == ButtonType.OK ? wif.getText() : null);

        Optional<String> answer = dialog.showAndWait();
        if (answer.isEmpty() || answer.get() == null) {
            return null;
        }

        String typed = answer.get().trim();
        if (!typed.isEmpty() && !ProvingKey.looksLikeWif(typed)) {
            AppServices.showErrorDialog("Invalid Key", "That does not look like a WIF private key.");
            return null;
        }
        return typed;
    }

    /**
     * Generate the proof for a pool, from a typed key or a wallet coin.
     *
     * This talks to the aut-ct server and can take a while, so it must not run on the fx thread.
     */
    public static String proveFor(JoinstrPool pool, String typedProvingKey) {
        String keyset = pool.getAutctKeyset();
        if (keyset == null || keyset.isEmpty()) {
            return null;
        }

        String wif = typedProvingKey != null && ProvingKey.looksLikeWif(typedProvingKey)
                ? typedProvingKey.trim()
                : walletProvingKey(pool);

        if (wif == null) {
            logger.severe("No Taproot key available for the aut-ct proof");
            return null;
        }

        AutctClient client = new AutctClient(Config.get().getAutctApiUrl());
        AutctClient.Proof proof = client.generateProof(wif, keyset,
                AutctPool.context(pool.getPoolId(), pool.getPubkey()));

        return proof == null ? null : proof.proof();
    }

    private static String walletProvingKey(JoinstrPool pool) {
        try {
            Map<Wallet, Storage> wallets = AppServices.get().getOpenWallets();
            for (Wallet wallet : wallets.keySet()) {
                String wif = ProvingKey.fromWallet(wallet, AppServices.getCurrentBlockHeight(),
                        0, 0, "p2tr");
                if (wif != null) {
                    return wif;
                }
            }
        } catch (Exception e) {
            logger.severe("Could not take a proving key from the wallet: " + e.getMessage());
        }
        return null;
    }
}
