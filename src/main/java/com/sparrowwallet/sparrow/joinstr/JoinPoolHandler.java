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
import nostr.api.NIP04;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.event.Kind;
import nostr.event.tag.PubKeyTag;
import nostr.id.Identity;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeoutException;
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
    private AtomicBoolean isOutputRegistered;

    public JoinPoolHandler(Identity joinIdentity, JoinstrPool pool, Consumer<String> statusCallback) {
        this.joinIdentity = joinIdentity;
        this.pool = pool;
        this.relay = pool.getRelay();
        this.statusCallback = statusCallback;
        this.numPeers = Integer.parseInt(pool.getPeers());
        this.isOutputRegistered = new AtomicBoolean(false);
    }

    public int getConnectedPeers() {
        return 0; // TODO fix, was "outputAddresses.size();"
    }

    /**
     * Start listening for credentials after sending join request
     */
    public void startListeningForCredentials() {

        Platform.runLater(() -> statusCallback.accept("Waiting for credentials"));

        credentialsListener = new NostrListener(joinIdentity, relay, null);

        credentialsListener.startListening(decryptedMessage -> {
            if (decryptedMessage.contains("\"id\"") && decryptedMessage.contains("\"private_key\"") && !isOutputRegistered.get()) {
                handleCredentialsReceived(decryptedMessage);
            }
        });

        // Schedule thread to stop listening after pool timeout
        new Thread(() -> {
            try {
                Thread.sleep((Long.parseLong(pool.getTimeout()) - Instant.now().getEpochSecond()) * 1000);
                credentialsListener.stop();
                MyPoolsController.clearPoolList();
                logger.info("Pool expired, stopping listener");
            } catch (InterruptedException e) {
                logger.warning("Error stopping listening thread: " + e.getMessage());
            } finally {
                try {
                    credentialsListener.stop();
                } catch (TimeoutException e) {
                    logger.warning("Error stopping credentials listener: " + e.getMessage());
                }
            }
        }).start();

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

            Gson gson = new Gson();
            Map<String, Object> credentials = gson.fromJson(credentialsJson, Map.class);

            String poolPrivateKey = credentials.get("private_key").toString();
            poolIdentity = Identity.create(poolPrivateKey);

            JoinstrPool poolWithCredentials = new JoinstrPool(
                    credentials.get("relay").toString(),
                    credentials.get("public_key").toString(),
                    pool.getDenomination(),
                    credentials.get("peers").toString(),
                    credentials.get("timeout").toString(),
                    poolPrivateKey);

            ArrayList<JoinstrPool> pools = Config.get().getPoolStore();
            pools.removeIf(p -> p.getPubkey().equals(poolWithCredentials.getPubkey()));
            pools.add(poolWithCredentials);
            Config.get().setPoolStore(pools);
            JoinstrPool.savePoolsFile(Storage.getJoinstrPoolsFile().getPath());

            this.pool = poolWithCredentials;

            try {
                credentialsListener.stop();
            } catch (Exception e) {
                logger.warning("Error stopping credentials listener: " + e.getMessage());
            }

            Platform.runLater(() -> statusCallback.accept("Credentials received"));

            // Use CoinjoinHandler for the rest of the flow
            startCoinjoinFlow();

        } catch (Exception e) {
            logger.severe("Error processing credentials: " + e.getMessage());
            e.printStackTrace();
            Platform.runLater(() -> statusCallback.accept("Error: " + e.getMessage()));
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
            Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage> firstWallet = openWallets.entrySet().iterator().next();
            com.sparrowwallet.drongo.wallet.Wallet wallet = firstWallet.getKey();
            Storage storage = firstWallet.getValue();

            WalletForm walletForm = new WalletForm(storage, wallet);
            NodeEntry freshEntry = walletForm.getFreshNodeEntry(KeyPurpose.RECEIVE, null);
            Address myOutputAddress = freshEntry.getAddress();

            // Create CoinjoinHandler with pool identity
            coinjoinHandler = new CoinjoinHandler(poolIdentity, pool, statusCallback);

            // Set callback to show UTXO selection dialog when all outputs collected
            final com.sparrowwallet.drongo.wallet.Wallet walletRef = wallet;
            coinjoinHandler.setOnReadyForInputCallback(() -> {
                showUtxoSelectionDialog(walletRef);
            });

            // Start output phase
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
     * Called by UI when user selects a UTXO.
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

    /**
     * Check if ready for input registration (all outputs collected)
     */
    public boolean isReadyForInputPhase() {
        return coinjoinHandler != null && coinjoinHandler.isReadyForInputPhase();
    }

    /**
     * Get the CoinjoinHandler for UI access
     */
    public CoinjoinHandler getCoinjoinHandler() {
        return coinjoinHandler;
    }

    /**
     * Show UTXO selection dialog and register input with selected UTXO
     */
    private void showUtxoSelectionDialog(com.sparrowwallet.drongo.wallet.Wallet wallet) {
        try {
            // Filter UTXOs by pool denomination
            long poolAmountSats = coinjoinHandler.getPoolAmountSats();

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
                credentialsListener.stop();
            }
            if (coinjoinHandler != null) {
                coinjoinHandler.stopListening();
            }
        } catch (Exception e) {
            logger.warning("Error stopping listeners: " + e.getMessage());
        }
    }
}
