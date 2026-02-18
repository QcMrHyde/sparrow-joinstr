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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class JoinPoolHandler implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(JoinPoolHandler.class.getName());
    private final Identity joinIdentity;
    private final String poolRelay;
    private final String poolPubkey;
    private final String poolDenomination;
    private final String poolPeers;
    private final String poolTimeout;
    private volatile String poolPrivateKey = "";
    private transient NostrListener credentialsListener;
    private transient NostrListener poolMessageListener;
    private volatile Identity poolIdentity;
    private final List<String> outputAddresses = new CopyOnWriteArrayList<>();
    private final int numPeers;
    private final Consumer<String> statusCallback;
    private final AtomicBoolean isCredentialsReceived = new AtomicBoolean(false);

    private transient ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2);
    private transient ScheduledFuture<?> futureStopCredListener;
    private transient ScheduledFuture<?> futureStopMsgListener;

    public JoinPoolHandler(Identity joinIdentity, JoinstrPool pool, Consumer<String> statusCallback) {
        this.joinIdentity = joinIdentity;
        this.poolRelay = pool.getRelay();
        this.poolPubkey = pool.getPubkey();
        this.poolDenomination = pool.getDenomination();
        this.poolPeers = pool.getPeers();
        this.poolTimeout = pool.getTimeout();
        this.statusCallback = statusCallback;

        String peersStr = pool.getPeers();
        try {
            if (peersStr == null || peersStr.trim().isEmpty()) {
                throw new IllegalArgumentException("Pool peers data is null or empty");
            }
            if (peersStr.contains("/")) {
                String[] parts = peersStr.split("/");
                if (parts.length < 2) {
                    throw new IllegalArgumentException(
                            "Invalid peers format: expected 'current/total', got: " + peersStr);
                }
                this.numPeers = Integer.parseInt(parts[1].trim());
            } else {
                this.numPeers = Integer.parseInt(peersStr.trim());
            }
        } catch (NumberFormatException e) {
            logger.severe("Failed to parse peers count from pool data: " + peersStr);
            throw new IllegalArgumentException("Invalid peers count format: " + peersStr, e);
        } catch (IllegalArgumentException e) {
            logger.severe("Invalid pool peers data: " + e.getMessage());
            throw e;
        }

        if (pool.getPrivateKey() != null && !pool.getPrivateKey().isEmpty()) {
            this.poolPrivateKey = pool.getPrivateKey();
            this.poolIdentity = Identity.create(this.poolPrivateKey);
        }

    }

    private ScheduledExecutorService getSchedulerService() {
        synchronized (this) {
            if (scheduledExecutorService.isShutdown() || scheduledExecutorService.isTerminated()) {
                scheduledExecutorService = Executors.newScheduledThreadPool(2);
            }
            return scheduledExecutorService;
        }
    }

    public int getConnectedPeers() {
        return outputAddresses.size();
    }

    public String getPoolPrivateKey() {
        return poolPrivateKey;
    }

    /**
     * Start listening for credentials after sending join request
     */
    public void startListeningForCredentials() {
        if (poolPrivateKey != null && !poolPrivateKey.isEmpty()) {
            registerOutput();
            return;
        }

        try {

            long msLeft = (Long.parseLong(poolTimeout) - Instant.now().getEpochSecond()) * 1000;
            if (msLeft > 1000) {
                Platform.runLater(() -> statusCallback.accept("Waiting for credentials"));

                credentialsListener = new NostrListener(joinIdentity, poolRelay, null);

                credentialsListener.startListening(decryptedMessage -> {
                    try {

                        Gson gson = new Gson();
                        Type mapType = new TypeToken<Map<String, Object>>() {
                        }.getType();
                        Map<String, Object> decryptedMessageGSON = gson.fromJson(decryptedMessage, mapType);

                        if (decryptedMessageGSON.get("id") != null && decryptedMessageGSON.get("private_key") != null
                                && isCredentialsReceived.compareAndSet(false, true)) {
                            handleCredentialsReceived(decryptedMessage);
                        }

                    } catch (Exception e) {
                        logger.warning("Error: " + e.getMessage());
                    }
                });

                // Schedule thread to stop listening after pool timeout
                futureStopCredListener = getSchedulerService().schedule(() -> {
                    logger.info("Pool expired, stopping listener");
                    try {
                        if (credentialsListener != null)
                            credentialsListener.close();
                    } catch (TimeoutException e) {
                        logger.warning("Error: " + e.getMessage());
                    }
                }, msLeft, TimeUnit.MILLISECONDS);

            }

        } catch (RuntimeException e) {
            logger.warning("Error: " + e.getMessage());
        }
    }

    /**
     * Handle received pool credentials
     */
    private void handleCredentialsReceived(String credentialsJson) {

        try {

            Gson gson = new Gson();
            Type mapType = new TypeToken<Map<String, Object>>() {
            }.getType();
            Map<String, Object> credentials = gson.fromJson(credentialsJson, mapType);

            if (credentials.get("private_key") != null)
                poolPrivateKey = credentials.get("private_key").toString();

            if (!poolPrivateKey.isEmpty()) {

                poolIdentity = Identity.create(poolPrivateKey);

                JoinstrPool poolWithCredentials = new JoinstrPool(
                        poolRelay,
                        poolPubkey,
                        poolDenomination,
                        poolPeers,
                        poolTimeout,
                        poolPrivateKey);

                ArrayList<JoinstrPool> pools = Config.get().getPoolStore();
                pools.removeIf(p -> p.getPubkey().equals(poolWithCredentials.getPubkey()));
                pools.add(poolWithCredentials);
                Config.get().setPoolStore(pools);
                JoinstrPool.savePoolsFile(Storage.getJoinstrPoolsFile().getPath());

                try {
                    if (credentialsListener != null) {
                        credentialsListener.close();
                        if (futureStopCredListener != null && !futureStopCredListener.isCancelled()
                                && !futureStopCredListener.isDone())
                            futureStopCredListener.cancel(true);
                    }
                } catch (Exception e) {
                    logger.warning("Error: " + e.getMessage());
                }

                Platform.runLater(() -> {
                    if (statusCallback != null) {
                        statusCallback.accept("Credentials received");
                    }
                });

                registerOutput();
            }
        } catch (Exception e) {
            logger.severe("Error: " + e.getMessage());
            e.printStackTrace();
            Platform.runLater(() -> {
                if (statusCallback != null) {
                    statusCallback.accept("Error: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Register output address using pool credentials
     */
    private void registerOutput() {
        try {
            Map<com.sparrowwallet.drongo.wallet.Wallet, Storage> openWallets = AppServices.get().getOpenWallets();

            if (openWallets.isEmpty()) {
                throw new IllegalStateException("No wallet found");
            }

            // AtomicReference to hold the selected wallet from the FX thread
            final AtomicReference<Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage>> selectedWalletRef = new AtomicReference<>();
            final AtomicReference<Exception> exceptionRef = new AtomicReference<>();

            if (openWallets.keySet().stream().filter(Wallet::isValid).count() > 1) {
                // Multiple wallets - need to show dialog on JavaFX thread
                final CountDownLatch latch = new CountDownLatch(1);
                Runnable walletSelectionAction = () -> {
                    try {
                        WalletSelectionDialog walletSelectionDialog = new WalletSelectionDialog(openWallets);
                        walletSelectionDialog.showAndWait();
                        selectedWalletRef.set(walletSelectionDialog.getSelectedWallet());
                    } catch (Exception e) {
                        exceptionRef.set(e);
                    } finally {
                        latch.countDown();
                    }
                };

                // Wait for the dialog to complete
                try {
                    if (!Platform.isFxApplicationThread()) {
                        Platform.runLater(walletSelectionAction);
                        latch.await();
                    } else {
                        walletSelectionAction.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for wallet selection");
                }

                // Check if there was an exception in the FX thread
                if (exceptionRef.get() != null) {
                    throw exceptionRef.get();
                }

                if (selectedWalletRef.get() == null) {
                    throw new IllegalStateException("No wallet selected");
                }
            } else {
                // Single wallet - no dialog needed
                selectedWalletRef.set(openWallets.entrySet().iterator().next());
            }

            Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage> selectedWallet = selectedWalletRef.get();
            com.sparrowwallet.drongo.wallet.Wallet wallet = selectedWallet.getKey();
            Storage storage = selectedWallet.getValue();

            WalletForm walletForm = new WalletForm(storage, wallet);
            NodeEntry freshEntry = walletForm.getFreshNodeEntry(KeyPurpose.RECEIVE, null);
            Address myOutputAddress = freshEntry.getAddress();

            Gson gson = new Gson();
            Map<String, String> outputMap = new LinkedHashMap<>();
            outputMap.put("type", "output");
            outputMap.put("address", myOutputAddress.toString());
            String outputContent = gson.toJson(outputMap);

            List<BaseTag> tags = new ArrayList<>();
            tags.add(new PubKeyTag(poolIdentity.getPublicKey()));

            NIP04 nip04 = new NIP04(poolIdentity, poolIdentity.getPublicKey());
            String encryptedContent = nip04.encrypt(poolIdentity, outputContent, poolIdentity.getPublicKey());

            GenericEvent outputEvent = new GenericEvent(
                    poolIdentity.getPublicKey(),
                    Kind.ENCRYPTED_DIRECT_MESSAGE.getValue(),
                    tags,
                    encryptedContent);

            nip04.setEvent(outputEvent);
            nip04.sign();
            nip04.send(Map.of("default", poolRelay));

            logger.info("Output registered: " + myOutputAddress);
            outputAddresses.add(myOutputAddress.toString());

            Platform.runLater(() -> {
                if (statusCallback != null) {
                    statusCallback.accept("Output registered");
                }
            });

            listenForOutputs();

        } catch (Exception e) {
            logger.severe("Error: " + e.getMessage());
            e.printStackTrace();
            Platform.runLater(() -> {
                if (statusCallback != null) {
                    statusCallback.accept("Error: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Listen for outputs from other peers using pool identity
     */
    private void listenForOutputs() {
        try {
            long msLeft = (Long.parseLong(poolTimeout) - Instant.now().getEpochSecond()) * 1000;
            if (msLeft > 1000) {
                poolMessageListener = new NostrListener(poolIdentity, poolRelay, getPoolCredentialsMap());
                poolMessageListener.startListening(decryptedMessage -> {
                    try {
                        Gson gson = new Gson();
                        Type mapType = new TypeToken<Map<String, Object>>() {
                        }.getType();
                        Map<String, Object> decryptedMessageGSON = gson.fromJson(decryptedMessage, mapType);

                        if (decryptedMessageGSON.get("type") != null
                                && "output".equals(decryptedMessageGSON.get("type"))) {
                            handleOutputReceived(decryptedMessage);
                        }
                    } catch (Exception e) {
                        logger.warning("Error: " + e.getMessage());
                    }
                });

                // Schedule thread to stop listening after pool timeout
                futureStopMsgListener = getSchedulerService().schedule(() -> {
                    try {
                        logger.info("Pool expired, stopping listener");
                        if (poolMessageListener != null) {
                            poolMessageListener.close();
                        }
                    } catch (TimeoutException e) {
                        logger.warning("Error: " + e.getMessage());
                    }
                }, msLeft, TimeUnit.MILLISECONDS);
            }

        } catch (Exception e) {
            logger.warning("Error: " + e.getMessage());
        }
    }

    private void handleOutputReceived(String decryptedMessage) {
        try {
            Gson gson = new Gson();
            Map<String, String> outputData = gson.fromJson(decryptedMessage, Map.class);
            String address = outputData.get("address");

            if (address != null && !outputAddresses.contains(address)) {
                outputAddresses.add(address);
                Platform.runLater(() -> {
                    if (statusCallback != null) {
                        statusCallback.accept("Waiting for peers");
                    }
                });

                int currentSize = outputAddresses.size();
                if (currentSize == numPeers) {
                    Platform.runLater(() -> {
                        if (statusCallback != null) {
                            statusCallback.accept("Input registration");
                        }
                    });
                    stop();
                }
            }
        } catch (Exception e) {
            logger.severe("Error: " + e.getMessage());
        }
    }

    public void stop() {

        try {
            synchronized (this) {
                if (credentialsListener != null) {
                    credentialsListener.close();
                    credentialsListener = null;
                    if (futureStopCredListener != null && !futureStopCredListener.isCancelled()
                            && !futureStopCredListener.isDone())
                        futureStopCredListener.cancel(true);
                }
            }
        } catch (Exception e) {
            logger.severe("Error: " + e.getMessage());
        }

        try {
            synchronized (this) {
                if (poolMessageListener != null) {
                    poolMessageListener.close();
                    poolMessageListener = null;
                    if (futureStopMsgListener != null && !futureStopMsgListener.isCancelled()
                            && !futureStopMsgListener.isDone())
                        futureStopMsgListener.cancel(true);
                }
            }
        } catch (Exception e) {
            logger.severe("Error: " + e.getMessage());
        }

        try {
            synchronized (this) {
                scheduledExecutorService.shutdown();
            }
        } catch (Exception e) {
            logger.severe("Error: " + e.getMessage());
        }

    }

    @Override
    public void close() throws Exception {
        stop();
    }

    private Map<String, String> getPoolCredentialsMap() {
        Map<String, String> map = new HashMap<>();
        // Use poolPubkey as ID since we don't store the event ID and it is unique
        // enough for verify
        map.put("id", poolPubkey);
        map.put("public_key", poolPubkey);
        map.put("denomination", poolDenomination);
        map.put("peers", poolPeers);
        map.put("timeout", poolTimeout);
        map.put("relay", poolRelay);
        map.put("private_key", poolPrivateKey);
        return map;
    }
}
