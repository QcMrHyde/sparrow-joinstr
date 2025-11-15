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
import nostr.base.PublicKey;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.event.Kind;
import nostr.event.tag.PubKeyTag;
import nostr.id.Identity;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class JoinPoolHandler {
    private static final Logger logger = Logger.getLogger(JoinPoolHandler.class.getName());

    private Identity joinIdentity;
    private JoinstrPool pool;
    private String relay;
    private NostrListener credentialsListener;
    private NostrListener poolMessageListener;
    private Identity poolIdentity;
    private List<String> outputAddresses = new CopyOnWriteArrayList<>();
    private int numPeers;
    private Consumer<String> statusCallback;

    public JoinPoolHandler(Identity joinIdentity, JoinstrPool pool, Consumer<String> statusCallback) {
        this.joinIdentity = joinIdentity;
        this.pool = pool;
        this.relay = pool.getRelay();
        this.statusCallback = statusCallback;

        String peersStr = pool.getPeers();
        if (peersStr.contains("/")) {
            this.numPeers = Integer.parseInt(peersStr.split("/")[1]);
        } else {
            this.numPeers = Integer.parseInt(peersStr);
        }
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
    private void handleCredentialsReceived(String credentialsJson) {
        try {
            logger.info("Received credentials: " + credentialsJson);

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

            registerOutput();

        } catch (Exception e) {
            logger.severe("Error processing credentials: " + e.getMessage());
            e.printStackTrace();
            Platform.runLater(() -> statusCallback.accept("Error" + e.getMessage()));
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
            Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage> firstWallet = openWallets.entrySet().iterator().next();
            com.sparrowwallet.drongo.wallet.Wallet wallet = firstWallet.getKey();
            Storage storage = firstWallet.getValue();

            WalletForm walletForm = new WalletForm(storage, wallet);
            NodeEntry freshEntry = walletForm.getFreshNodeEntry(KeyPurpose.RECEIVE, null);
            Address myOutputAddress = freshEntry.getAddress();

            String outputContent = String.format(
                    "{\"type\":\"output\",\"address\":\"%s\"}",
                    myOutputAddress.toString()
            );

            List<BaseTag> tags = new ArrayList<>();
            tags.add(new PubKeyTag(poolIdentity.getPublicKey()));

            NIP04 nip04 = new NIP04(poolIdentity, poolIdentity.getPublicKey());
            String encryptedContent = nip04.encrypt(poolIdentity, outputContent, poolIdentity.getPublicKey());

            GenericEvent outputEvent = new GenericEvent(
                    poolIdentity.getPublicKey(),
                    Kind.ENCRYPTED_DIRECT_MESSAGE.getValue(),
                    tags,
                    encryptedContent
            );

            nip04.setEvent(outputEvent);
            nip04.sign();
            nip04.send(Map.of("default", relay));

            logger.info("Output registered: " + myOutputAddress);
            outputAddresses.add(myOutputAddress.toString());

            Platform.runLater(() -> statusCallback.accept("Output registered"));

            listenForOutputs();

        } catch (Exception e) {
            logger.severe("Error registering output: " + e.getMessage());
            e.printStackTrace();
            Platform.runLater(() -> statusCallback.accept("Error" + e.getMessage()));
        }
    }

    /**
     * Listen for outputs from other peers using pool identity
     */
    private void listenForOutputs() {
        poolMessageListener = new NostrListener(poolIdentity, relay, null);

        poolMessageListener.startListening(decryptedMessage -> {
            if (decryptedMessage.contains("\"type\": \"output\"")) {
                handleOutputReceived(decryptedMessage);
            }
        });
    }

    private void handleOutputReceived(String decryptedMessage) {
        try {
            Gson gson = new Gson();
            Map<String, String> outputData = gson.fromJson(decryptedMessage, Map.class);
            String address = outputData.get("address");

            if (address != null && !outputAddresses.contains(address)) {
                outputAddresses.add(address);

                Platform.runLater(() -> statusCallback.accept(
                        "Waiting for peers"
                ));

                if (outputAddresses.size() >= numPeers) {
                    Platform.runLater(() -> statusCallback.accept("Input registration"));
                }
            }
        } catch (Exception e) {
            logger.severe("Error handling output: " + e.getMessage());
        }
    }

    public void stop() {
        try {
            if (credentialsListener != null) {
                credentialsListener.stop();
            }
            if (poolMessageListener != null) {
                poolMessageListener.stop();
            }
        } catch (Exception e) {
            logger.warning("Error stopping listeners: " + e.getMessage());
        }
    }
}