package com.sparrowwallet.sparrow.joinstr;

import com.google.gson.Gson;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.wallet.NodeEntry;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.application.Platform;
import nostr.id.Identity;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class JoinPoolHandler {
    private static final Logger logger = Logger.getLogger(JoinPoolHandler.class.getName());

    private Identity joinIdentity;
    private JoinstrPool pool;
    private String relay;
    private NostrListener credentialsListener;
    private Identity poolIdentity;
    private int numPeers;
    private Consumer<String> statusCallback;
    private CoinjoinHandler coinjoinHandler;

    public JoinPoolHandler(Identity joinIdentity, JoinstrPool pool, Consumer<String> statusCallback) {
        this.joinIdentity = joinIdentity;
        this.pool = pool;
        this.relay = pool.getRelay();
        this.statusCallback = statusCallback;

        this.numPeers = pool.getParsedPeers();
    }

    /**
     * Start listening for credentials after sending join request
     */
    public void startListeningForCredentials() {
        Platform.runLater(() -> statusCallback.accept("Waiting for credentials"));

        credentialsListener = new NostrListener(joinIdentity, relay, null);

        credentialsListener.startListening(decryptedMessage -> {
            if (decryptedMessage.contains("\"id\"") && decryptedMessage.contains("\"private_key\"")) {
                handleCredentialsReceived(decryptedMessage);
            }
        });
    }

    /**
     * Handle received pool credentials
     */
    private final AtomicBoolean credentialsReceived = new AtomicBoolean(false);

    private void handleCredentialsReceived(String credentialsJson) {
        if (!credentialsReceived.compareAndSet(false, true)) {
            logger.warning("Credentials already received, ignoring duplicate message");
            return;
        }

        try {
            logger.info("Received credentials: " + credentialsJson);

            Gson gson = new Gson();
            Map<String, Object> credentials = gson.fromJson(credentialsJson, Map.class);

            String poolPrivateKey = credentials.get("private_key").toString();
            this.poolPrivateKeyString = poolPrivateKey;
            poolIdentity = Identity.create(poolPrivateKey);

            this.pool.setPrivateKey(poolPrivateKey);

            ArrayList<JoinstrPool> pools = Config.get().getPoolStore();
            boolean updated = false;
            for (int i = 0; i < pools.size(); i++) {
                if (pools.get(i).getPubkey().equals(pool.getPubkey())) {
                    pools.set(i, pool);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                pools.add(pool);
            }
            Config.get().setPoolStore(pools);
            JoinstrPool.savePoolsFile(Storage.getJoinstrPoolsFile().getPath());

            try {
                if (credentialsListener != null) {
                    credentialsListener.close();
                }
            } catch (Exception e) {
                logger.warning("Error stopping credentials listener: " + e.getMessage());
            }

            Platform.runLater(() -> statusCallback.accept("Credentials received"));

            // Use CoinjoinHandler for the rest of the flow
            startCoinjoinFlow();

        } catch (Exception e) {
            logger.severe("Error processing credentials: " + e.getMessage());
            e.printStackTrace();
            Platform.runLater(() -> statusCallback.accept("Error " + e.getMessage()));
        }
    }

    /**
     * Start the coinjoin flow using CoinjoinHandler
     */
    private void startCoinjoinFlow() {
        try {
            Map<com.sparrowwallet.drongo.wallet.Wallet, Storage> openWallets = AppServices.get().getOpenWallets();
            if (openWallets.isEmpty()) {
                throw new IllegalStateException("No wallet found");
            }
            Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage> firstWallet = openWallets.entrySet().iterator()
                    .next();
            com.sparrowwallet.drongo.wallet.Wallet wallet = firstWallet.getKey();
            Storage storage = firstWallet.getValue();

            WalletForm walletForm = new WalletForm(storage, wallet);
            NodeEntry freshEntry = walletForm.getFreshNodeEntry(KeyPurpose.RECEIVE, null);
            Address myOutputAddress = freshEntry.getAddress();

            coinjoinHandler = new CoinjoinHandler(poolIdentity, pool, wallet, storage, statusCallback);

            final com.sparrowwallet.drongo.wallet.Wallet walletRef = wallet;
            coinjoinHandler.setOnReadyForInputCallback(() -> {
                showUtxoSelectionDialog(walletRef);
            });

            coinjoinHandler.startOutputPhase(myOutputAddress.toString());
            logger.info("Started coinjoin flow with output address: " + myOutputAddress);

        } catch (Exception e) {
            logger.severe("Error starting coinjoin flow: " + e.getMessage());
            e.printStackTrace();
            Platform.runLater(() -> statusCallback.accept("Error: " + e.getMessage()));
        }
    }

    /**
     * Trigger input registration with selected UTXO.
     */
    public void registerInput(com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex utxo,
            com.sparrowwallet.drongo.wallet.WalletNode utxoNode) {
        if (coinjoinHandler != null) {
            coinjoinHandler.startInputPhase(utxo, utxoNode);
        } else {
            logger.severe("CoinjoinHandler not initialized");
            Platform.runLater(() -> statusCallback.accept("Error: Handler not ready"));
        }
    }

    public boolean isReadyForInputPhase() {
        return coinjoinHandler != null && coinjoinHandler.isReadyForInputPhase();
    }

    public CoinjoinHandler getCoinjoinHandler() {
        return coinjoinHandler;
    }

    /**
     * Show UTXO selection dialog and register input with selected UTXO
     */
    private void showUtxoSelectionDialog(com.sparrowwallet.drongo.wallet.Wallet wallet) {
        try {
            long poolAmountSats = coinjoinHandler.getPoolAmountSats();

            com.sparrowwallet.sparrow.joinstr.control.UtxoCircleDialog dialog = new com.sparrowwallet.sparrow.joinstr.control.UtxoCircleDialog(
                    wallet);
            dialog.setTitle("Select UTXO for Coinjoin");
            dialog.showAndWait();

            java.util.Set<com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex> selectedUtxos = dialog
                    .getSelectedUtxos();

            if (selectedUtxos != null && !selectedUtxos.isEmpty()) {
                com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex selectedUtxo = selectedUtxos.iterator()
                        .next();

                java.util.Map<com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex, com.sparrowwallet.drongo.wallet.WalletNode> utxoMap = wallet
                        .getWalletUtxos();
                com.sparrowwallet.drongo.wallet.WalletNode utxoNode = utxoMap.get(selectedUtxo);

                logger.info("Selected UTXO: " + selectedUtxo.getHash() + ":" + selectedUtxo.getIndex() + " value="
                        + selectedUtxo.getValue());

                coinjoinHandler.startInputPhase(selectedUtxo, utxoNode);
            } else {
                logger.warning("No UTXO selected, input registration cancelled");
                Platform.runLater(() -> statusCallback.accept("Input registration cancelled"));
            }
        } catch (Exception e) {
            logger.severe("Error showing UTXO dialog: " + e.getMessage());
            e.printStackTrace();
            Platform.runLater(() -> statusCallback.accept("Error: " + e.getMessage()));
        }
    }

    public void stop() {
        try {
            if (credentialsListener != null) {
                credentialsListener.close();
            }
            if (coinjoinHandler != null) {
                coinjoinHandler.stopListening();
            }
        } catch (Exception e) {
            logger.warning("Error stopping listeners: " + e.getMessage());
        }
    }

    public int getConnectedPeers() {
        if (coinjoinHandler != null) {
            return coinjoinHandler.getOutputAddresses().size();
        }
        return 0;
    }

    public String getPoolPrivateKey() {
        if (poolIdentity != null) {
            return poolPrivateKeyString;
        }
        return "";
    }

    private String poolPrivateKeyString = "";
}
