package com.sparrowwallet.sparrow.joinstr;

import static com.sparrowwallet.sparrow.AppServices.showSuccessDialog;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.joinstr.control.WalletSelectionDialog;
import com.sparrowwallet.sparrow.wallet.PaymentController;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import javafx.fxml.FXML;
import javafx.scene.control.TextField;

import nostr.event.impl.GenericEvent;
import nostr.id.Identity;

public class NewPoolController extends JoinstrFormController {

    private static final Logger logger = Logger.getLogger(NostrListener.class.getName());

    private CoinjoinHandler coinjoinHandler;

    @FXML
    private TextField denominationField;

    @FXML
    private TextField peersField;

    @Override
    public void initializeView() {
    }

    @FXML
    private void handleCreateButton() {
        try {

            String denomination = denominationField.getText().trim();
            String peers = peersField.getText().trim();

            if (denomination.isEmpty() || peers.isEmpty()) {
                showError("Please enter denomination and peers to create a pool");
                return;
            }

            try {
                double denominationValue = Double.parseDouble(denomination);
                DecimalFormat df = new DecimalFormat("#.########", DecimalFormatSymbols.getInstance(Locale.US));
                denomination = df.format(denominationValue);
                if (denominationValue <= 0) {
                    showError("Denomination must be greater than zero");
                    return;
                }
            } catch (NumberFormatException e) {
                showError("Invalid denomination format");
                return;
            }

            try {
                int peersValue = Integer.parseInt(peers);
                if (peersValue <= 1) {
                    showError("Number of peers must be greater than 1");
                    return;
                }
            } catch (NumberFormatException e) {
                showError("Invalid number of peers format");
                return;
            }

            Address bitcoinAddress;
            Wallet wallet;
            try {

                Map<Wallet, Storage> openWallets = AppServices.get().getOpenWallets();
                Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage> selectedWallet;

                if (openWallets.isEmpty()) {
                    throw new Exception("No wallet found. Please open a wallet in Sparrow first.");
                } else if(openWallets.keySet().stream().filter(Wallet::isValid).count() > 1) {
                    WalletSelectionDialog walletSelectionDialog = new WalletSelectionDialog(openWallets);
                    walletSelectionDialog.showAndWait();
                    selectedWallet = walletSelectionDialog.getSelectedWallet();
                } else {
                    selectedWallet = openWallets.entrySet().iterator().next();
                }

                wallet = selectedWallet.getKey();
                Storage storage = selectedWallet.getValue();
                bitcoinAddress = NostrPublisher.getNewReceiveAddress(storage, wallet);

                double recipientDustThreshold = (double) PaymentController.getRecipientDustThreshold(bitcoinAddress)
                        / 100000000;
                if (Double.parseDouble(denomination) <= recipientDustThreshold) {
                    throw new Exception("Denomination must be greater than recipient dust threshold ("
                            + recipientDustThreshold + ")");
                }

            } catch (Exception e) {
                showError(e.getMessage());
                return;
            }

            GenericEvent event = null;
            try {

                event = NostrPublisher.publishCustomEvent(denomination, peers, bitcoinAddress.toString());
                assert event != null;

                String poolPrivateKey = NostrPublisher.getPoolPrivateKey();
                JoinstrEvent joinstrEvent = new JoinstrEvent(event.getContent());

                JoinstrPool pool = new JoinstrPool(joinstrEvent.relay, joinstrEvent.public_key,
                        joinstrEvent.denomination, joinstrEvent.peers, joinstrEvent.timeout, poolPrivateKey);
                updatePoolStore(pool);

                getJoinstrController().setSelectedPool(pool);
                getJoinstrController().setJoinstrDisplay(JoinstrDisplay.MY_POOLS);

                // Start CoinjoinHandler for pool creator flow
                startCreatorCoinjoinFlow(pool, poolPrivateKey, bitcoinAddress.toString(), wallet);

            } catch (Exception e) {
                showError("Error: " + e.getMessage());
            }

            denominationField.clear();
            peersField.clear();

            assert event != null;
            showSuccessDialog(
                    "New Pool",
                    "Pool created successfully!\nEvent ID: " + event.getId() +
                            "\nDenomination: " + denomination +
                            "\nPeers: " + peers +
                            "\n\nWaiting for peers to join...");

        } catch (Exception e) {
            showError("An error occurred: " + e.getMessage());
        }
    }

    /**
     * Start CoinjoinHandler for pool creator after pool is created
     */
    private void startCreatorCoinjoinFlow(JoinstrPool pool, String poolPrivateKey, String myOutputAddress,
            Wallet wallet) {
        try {
            Identity poolIdentity = Identity.create(poolPrivateKey);

            // Create CoinjoinHandler with status callback
            coinjoinHandler = new CoinjoinHandler(poolIdentity, pool, status -> {
                logger.info("Pool creator status: " + status);
                // Update pool status in UI if needed
            });

            // Set callback to show UTXO selection dialog when all outputs collected
            coinjoinHandler.setOnReadyForInputCallback(() -> {
                showUtxoSelectionDialog(wallet);
            });

            // Start output phase with creator's output address
            coinjoinHandler.startOutputPhase(myOutputAddress);
            logger.info("Pool creator started coinjoin flow with output: " + myOutputAddress);

            // Also start listening for join requests to share credentials
            Map<String, String> poolCredentials = new java.util.HashMap<>();
            poolCredentials.put("id", pool.getPubkey());
            poolCredentials.put("private_key", poolPrivateKey);
            poolCredentials.put("relay", pool.getRelay());
            poolCredentials.put("public_key", pool.getPubkey());
            poolCredentials.put("peers", pool.getPeers());
            poolCredentials.put("timeout", pool.getTimeout());

            shareCredentials(poolIdentity, pool.getRelay(), poolCredentials);

        } catch (Exception e) {
            logger.severe("Error starting creator coinjoin flow: " + e.getMessage());
            showError("Error starting coinjoin: " + e.getMessage());
        }
    }

    /**
     * Show UTXO selection dialog and register input with selected UTXO
     */
    private void showUtxoSelectionDialog(Wallet wallet) {
        try {
            UtxoCircleDialog dialog = new UtxoCircleDialog(wallet);
            dialog.setTitle("Select UTXO for Coinjoin");
            dialog.showAndWait();

            java.util.Set<com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex> selectedUtxos = dialog
                    .getSelectedUtxos();

            if (selectedUtxos != null && !selectedUtxos.isEmpty()) {
                // Get first selected UTXO
                com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex selectedUtxo = selectedUtxos.iterator()
                        .next();

                // Get the WalletNode for this UTXO
                java.util.Map<com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex, com.sparrowwallet.drongo.wallet.WalletNode> utxoMap = wallet
                        .getWalletUtxos();
                com.sparrowwallet.drongo.wallet.WalletNode utxoNode = utxoMap.get(selectedUtxo);

                logger.info("Selected UTXO: " + selectedUtxo.getHash() + ":" + selectedUtxo.getIndex() + " value="
                        + selectedUtxo.getValue());

                // Register the input
                coinjoinHandler.startInputPhase(selectedUtxo, utxoNode);
            } else {
                logger.warning("No UTXO selected, input registration cancelled");
            }
        } catch (Exception e) {
            logger.severe("Error showing UTXO dialog: " + e.getMessage());
            e.printStackTrace();
            showError("Error: " + e.getMessage());
        }
    }

    /**
     * Get the CoinjoinHandler for UI access (input registration trigger)
     */
    public CoinjoinHandler getCoinjoinHandler() {
        return coinjoinHandler;
    }

    private void updatePoolStore(JoinstrPool pool) throws IOException {

        ArrayList<JoinstrPool> pools = Config.get().getPoolStore();

        pools.removeIf(p -> p.getJoinstrIdentity() == pool.getJoinstrIdentity());
        pools.add(pool);
        Config.get().setPoolStore(pools);

        JoinstrPool.savePoolsFile(Storage.getJoinstrPoolsFile().getPath());

    }

    public static void shareCredentials(Identity poolIdentity, String relayUrl, Map<String, String> poolCredentials) {

        NostrListener listener = new NostrListener(poolIdentity, relayUrl, poolCredentials);

        listener.startListening(decryptedMessage -> {
            logger.info("Received message: " + decryptedMessage);
        });
    }

}