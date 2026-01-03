package com.sparrowwallet.sparrow.joinstr;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.joinstr.control.WalletSelectionDialog;
import com.sparrowwallet.sparrow.wallet.NodeEntry;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.application.Platform;
import nostr.api.NIP04;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.event.Kind;
import nostr.event.tag.PubKeyTag;
import nostr.id.Identity;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class JoinPoolHandler implements IThreadExecutor {
    private static final Logger logger = Logger.getLogger(JoinPoolHandler.class.getName());
    private final Identity joinIdentity;
    private JoinstrPool pool;
    private final String relay;
    private NostrListener credentialsListener;
    private Identity poolIdentity;
    private final int numPeers;
    private final Consumer<String> statusCallback;
    private CoinjoinHandler coinjoinHandler;
    private final AtomicBoolean isOutputRegistered;
    private final AtomicBoolean isCredentialsReceived = new AtomicBoolean(false);

    private Semaphore semaphore = new Semaphore(1);

    private ExecutorService threadPool = Executors.newFixedThreadPool(10, r -> {
        Thread t = Executors.defaultThreadFactory().newThread(r);
        t.setDaemon(true);
        return t;
    });

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

        try {

            long msLeft = (Long.parseLong(pool.getTimeout()) - Instant.now().getEpochSecond()) * 1000;
            if(msLeft > 1000) {
                Platform.runLater(() -> statusCallback.accept("Waiting for credentials"));

                credentialsListener = new NostrListener(joinIdentity, relay, null);

                credentialsListener.startListening(decryptedMessage -> {
                    try {
                        semaphore.acquire();

                        Gson gson = new Gson();
                        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
                        Map<String, Object> decryptedMessageGSON = gson.fromJson(decryptedMessage, mapType);

                        if (decryptedMessageGSON.get("id") != null && decryptedMessageGSON.get("private_key") != null && !isOutputRegistered.get()) {
                            handleCredentialsReceived(decryptedMessage);
                        }

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warning("Error stopping listening thread: " + e.getMessage());
                        throw new RuntimeException(e);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        semaphore.release();
                    }
                });

            // Schedule thread to stop listening after pool timeout
            threadPool.submit(() -> {
                try {
                    Thread.sleep(msLeft);
                    logger.info("Pool expired, stopping listener");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warning("Error stopping listening thread: " + e.getMessage());
                    throw new RuntimeException(e);
                } finally {
                    try {
                        credentialsListener.stop();
                    } catch (TimeoutException e) {
                        logger.warning("Error stopping credentials listener: " + e.getMessage());
                    }
                }
            });
        }

        } catch (RuntimeException e) {
            logger.warning("Error stopping threads: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Handle received pool credentials
     */
    private void handleCredentialsReceived(String credentialsJson) {

        if (!isCredentialsReceived.compareAndSet(false, true)) {
            logger.warning("Credentials already received, ignoring duplicate message");
            return;
        }
 
        try {

            Gson gson = new Gson();
            Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
            Map<String, Object> credentials = gson.fromJson(credentialsJson, mapType);

            String poolPrivateKey = "";
            if(credentials.get("private_key") != null)
                poolPrivateKey = credentials.get("private_key").toString();

            if(!poolPrivateKey.isEmpty()) {

                poolIdentity = Identity.create(poolPrivateKey);

                JoinstrPool poolWithCredentials = new JoinstrPool(
                        pool.getRelay(),
                        pool.getPubkey(),
                        pool.getDenomination(),
                        pool.getPeers(),
                        pool.getTimeout(),
                        poolPrivateKey
                );

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
            }

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
            Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage> selectedWallet;
            if (openWallets.isEmpty()) {
                throw new IllegalStateException("No wallet found");
            } else if(openWallets.keySet().stream().filter(Wallet::isValid).count() > 1) {
                WalletSelectionDialog walletSelectionDialog = new WalletSelectionDialog(openWallets);
                walletSelectionDialog.showAndWait();
                selectedWallet = walletSelectionDialog.getSelectedWallet();
            } else {
                selectedWallet = openWallets.entrySet().iterator().next();
            }

            com.sparrowwallet.drongo.wallet.Wallet wallet = selectedWallet.getKey();
            Storage storage = selectedWallet.getValue();

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

    private void shutdownThreads() {
        if (!threadPool.isShutdown()) {
            threadPool.shutdown();

            try {
                if (!threadPool.awaitTermination(3, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.severe("Error stopping threads: " + e.getMessage());
                threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
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
            shutdownThreads();
        } catch (Exception e) {
            logger.warning("Error stopping listeners: " + e.getMessage());
        }
    }

}
