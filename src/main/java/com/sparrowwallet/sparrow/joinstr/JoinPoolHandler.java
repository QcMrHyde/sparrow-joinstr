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
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import nostr.id.Identity;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class JoinPoolHandler {
    private static final Logger logger = Logger.getLogger(JoinPoolHandler.class.getName());

    /** Absolute bounds on an accepted pool fee rate (sat/vB). */
    static final double MIN_FEE_RATE = 1;
    static final double MAX_FEE_RATE = 100;

    private Identity joinIdentity;
    private JoinstrPool pool;
    private String relay;
    private NostrListener credentialsListener;
    private Identity poolIdentity;
    private int numPeers;
    private double feeRate = 1;
    private Consumer<String> statusCallback;
    private CoinjoinHandler coinjoinHandler;

    /**
     * Accept a fee rate that stays within a tolerance band of the advertised rate (it may drift
     * between pool creation and the coinjoin) and within the absolute [MIN_FEE_RATE, MAX_FEE_RATE] range.
     */
    static boolean isFeeRateAcceptable(double feeRate, double advertisedFeeRate) {
        if (!Double.isFinite(feeRate) || !Double.isFinite(advertisedFeeRate)) {
            return false;
        }
        double lo = Math.max(MIN_FEE_RATE, advertisedFeeRate / 2);
        double hi = Math.min(MAX_FEE_RATE, advertisedFeeRate * 2);
        return feeRate >= lo && feeRate <= hi;
    }

    /**
     * Handle one decrypted message received while waiting for credentials. This is what the relay
     * listener calls for every message addressed to the joiner's throwaway key.
     */
    void onDecryptedMessage(String decryptedMessage) {
        JoinstrMessage message;
        try {
            message = JoinstrMessage.fromJson(decryptedMessage);
        } catch (Exception e) {
            logger.warning("Ignoring unparseable message while waiting for credentials");
            return;
        }

        if (message == null) {
            return;
        }

        if ("reject".equals(message.getType())) {
            handleRejected(message);
            return;
        }

        if (message.getPrivateKey() == null) {
            return;
        }

        String rejection = credentialsRejectionReason(message, pool);
        if (rejection != null) {
            // nip 04 does not authenticate the sender and this key is public on the relay from
            // the moment the join request is published, so anyone can answer it. Keep waiting
            // for the pool that was actually chosen instead of taking the first reply.
            logger.warning("Ignoring credentials that did not come from the pool joined: " + rejection);
            return;
        }

        handleCredentialsReceived(message);
    }

    /** A readable explanation for a reject sent by a pool creator. */
    static String rejectionMessage(JoinstrMessage reject) {
        String reason = reject.getReason();
        if (reason == null || reason.trim().isEmpty()) {
            return "The pool creator rejected your request.";
        }

        String detail = switch (reason) {
            case "missing_proof" -> "An aut-ct proof is required to join this pool.";
            case "invalid_proof" -> "The provided aut-ct proof could not be verified.";
            case "duplicate_token" -> "The aut-ct token has already been used.";
            default -> reason;
        };

        return "The pool creator rejected your request: " + detail;
    }

    private void handleRejected(JoinstrMessage reject) {
        if (!rejected.compareAndSet(false, true)) {
            return;
        }

        String message = rejectionMessage(reject);
        logger.warning(message);
        FxDispatch.run(() -> statusCallback.accept("Rejected"));
        FxDispatch.run(() -> errorDialog.accept(message));
        stop();
    }

    /**
     * Why the credentials do not belong to this pool, or null if they do.
     *
     * The private key inside them must be the key of the pool that was joined, which nobody but
     * its creator holds, and every term must match what that pool advertised.
     */
    static String credentialsRejectionReason(JoinstrMessage credentials, JoinstrPool pool) {
        if (pool == null) {
            return "no pool to check against";
        }

        String derived;
        try {
            derived = Identity.create(credentials.getPrivateKey()).getPublicKey().toString();
        } catch (Exception e) {
            return "private key is not a nostr key";
        }

        if (!derived.equals(pool.getPubkey())) {
            return "private key is not the key of this pool";
        }

        String announcedId = pool.getPoolId();
        if (announcedId != null && !announcedId.isEmpty() && !announcedId.equals(credentials.getId())) {
            return "id does not match the announcement";
        }

        if (credentials.getDenomination() == null
                || sats(credentials.getDenomination()) != denominationSats(pool)) {
            return "denomination does not match the announcement";
        }

        if (credentials.getPeers() == null || credentials.getPeers() != pool.getParsedPeers()) {
            return "peers does not match the announcement";
        }

        Long announcedTimeout = parseTimeout(pool.getTimeout());
        if (credentials.getTimeout() == null || !credentials.getTimeout().equals(announcedTimeout)) {
            return "timeout does not match the announcement";
        }

        if (credentials.getRelay() == null || !credentials.getRelay().equals(pool.getRelay())) {
            return "relay does not match the announcement";
        }

        return null;
    }

    private static long sats(double denominationBtc) {
        return Math.round(denominationBtc * 100000000d);
    }

    private static long denominationSats(JoinstrPool pool) {
        try {
            return CoinjoinMath.denominationToSats(pool.getDenomination());
        } catch (Exception e) {
            return -1;
        }
    }

    private static Long parseTimeout(String timeout) {
        try {
            return Long.parseLong(timeout.trim());
        } catch (Exception e) {
            return null;
        }
    }

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
        FxDispatch.run(() -> statusCallback.accept("Waiting for credentials"));

        credentialsListener = new NostrListener(joinIdentity, relay, null);

        credentialsListener.startListening((message, createdAt) -> onDecryptedMessage(message));
    }

    /**
     * Handle received pool credentials
     */
    private final AtomicBoolean credentialsReceived = new AtomicBoolean(false);
    private final AtomicBoolean rejected = new AtomicBoolean(false);
    private Consumer<String> errorDialog = message -> AppServices.showErrorDialog("Join Request Rejected", message);

    private void handleCredentialsReceived(JoinstrMessage message) {
        if (!credentialsReceived.compareAndSet(false, true)) {
            logger.warning("Credentials already received, ignoring duplicate message");
            return;
        }

        try {
            String poolPrivateKey = message.getPrivateKey();
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

            FxDispatch.run(() -> statusCallback.accept("Credentials received"));

            double advertisedFeeRate = pool.getParsedFeeRate();
            double credentialsFeeRate = (message.getFeeRate() != null) ? message.getFeeRate() : advertisedFeeRate;

            if (!isFeeRateAcceptable(credentialsFeeRate, advertisedFeeRate)) {
                logger.severe("Rejecting pool: fee rate " + credentialsFeeRate
                        + " sat/vB is outside the accepted range for advertised " + advertisedFeeRate
                        + " sat/vB (absolute max " + MAX_FEE_RATE + ")");
                FxDispatch.run(() -> statusCallback.accept("Error: Fee rate out of range"));
                stop();
                return;
            }

            this.feeRate = credentialsFeeRate;

            // Use CoinjoinHandler for the rest of the flow
            startCoinjoinFlow(credentialsFeeRate);

        } catch (Exception e) {
            logger.severe("Error processing credentials: " + e.getMessage());
            e.printStackTrace();
            FxDispatch.run(() -> statusCallback.accept("Error " + e.getMessage()));
        }
    }

    /**
     * Start the coinjoin flow using CoinjoinHandler
     */
    private void startCoinjoinFlow(double feeRate) {
        try {
            Map<com.sparrowwallet.drongo.wallet.Wallet, Storage> openWallets = AppServices.get().getOpenWallets();
            if (openWallets.isEmpty()) {
                throw new IllegalStateException("No wallet found");
            }
            Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage> selectedWallet = selectWallet(openWallets);
            if (selectedWallet == null) {
                logger.warning("No wallet selected for coinjoin");
                FxDispatch.run(() -> statusCallback.accept("Error: No wallet selected"));
                return;
            }
            com.sparrowwallet.drongo.wallet.Wallet wallet = selectedWallet.getKey();
            Storage storage = selectedWallet.getValue();

            WalletForm walletForm = new WalletForm(storage, wallet);
            Address myOutputAddress = OutputAddress.fresh(walletForm);
            if (myOutputAddress == null) {
                throw new IllegalStateException("no receive address available for the coinjoin");
            }

            coinjoinHandler = new CoinjoinHandler(poolIdentity, pool, wallet, storage, statusCallback);
            coinjoinHandler.setFeeRate(feeRate);

            final com.sparrowwallet.drongo.wallet.Wallet walletRef = wallet;
            coinjoinHandler.setOnReadyForInputCallback(() -> {
                showUtxoSelectionDialog(walletRef);
            });

            coinjoinHandler.startOutputPhase(myOutputAddress.toString());
            logger.info("Started coinjoin flow");

        } catch (Exception e) {
            logger.severe("Error starting coinjoin flow: " + e.getMessage());
            e.printStackTrace();
            FxDispatch.run(() -> statusCallback.accept("Error: " + e.getMessage()));
        }
    }

    /**
     * Choose which open wallet to use for the coinjoin. With a single wallet it is used directly;
     * with several, the user is prompted so the output address and input UTXO come from the same
     * wallet they intend, rather than an arbitrary first entry.
     */
    private Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage> selectWallet(
            Map<com.sparrowwallet.drongo.wallet.Wallet, Storage> openWallets) throws Exception {
        if (openWallets.size() == 1) {
            return openWallets.entrySet().iterator().next();
        }

        java.util.concurrent.FutureTask<Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage>> task = new java.util.concurrent.FutureTask<>(
                () -> {
                    com.sparrowwallet.sparrow.joinstr.control.WalletSelectionDialog dialog = new com.sparrowwallet.sparrow.joinstr.control.WalletSelectionDialog(
                            openWallets);
                    dialog.showAndWait();
                    return dialog.getSelectedWallet();
                });

        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            FxDispatch.run(task);
        }
        return task.get();
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
            FxDispatch.run(() -> statusCallback.accept("Error: Handler not ready"));
        }
    }

    public boolean isReadyForInputPhase() {
        return coinjoinHandler != null && coinjoinHandler.isReadyForInputPhase();
    }

    void setErrorDialog(Consumer<String> errorDialog) {
        this.errorDialog = errorDialog;
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

                logger.info("UTXO selected for the coinjoin");

                long outputAmount = CoinjoinMath.outputAmount(poolAmountSats, feeRate,
                        coinjoinHandler.getNumPeers());
                long myFee = selectedUtxo.getValue() - outputAmount;

                java.util.Optional<ButtonType> confirm = AppServices.showAlertDialog(
                        "Confirm Coinjoin Input",
                        "Input: " + selectedUtxo.getValue() + " sats\n" +
                                "Output: " + outputAmount + " sats\n" +
                                "Fee: " + myFee + " sats (" + CoinjoinMath.formatFeeRate(feeRate) + " sat/vB)\n\n" +
                                "Proceed with signing?",
                        Alert.AlertType.CONFIRMATION, ButtonType.CANCEL, ButtonType.OK);

                if (confirm.isEmpty() || confirm.get() != ButtonType.OK) {
                    logger.info("User cancelled coinjoin input confirmation");
                    FxDispatch.run(() -> statusCallback.accept("Input registration cancelled"));
                    return;
                }

                coinjoinHandler.startInputPhase(selectedUtxo, utxoNode);
            } else {
                logger.warning("No UTXO selected, input registration cancelled");
                FxDispatch.run(() -> statusCallback.accept("Input registration cancelled"));
            }
        } catch (Exception e) {
            logger.severe("Error showing UTXO dialog: " + e.getMessage());
            e.printStackTrace();
            FxDispatch.run(() -> statusCallback.accept("Error: " + e.getMessage()));
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
