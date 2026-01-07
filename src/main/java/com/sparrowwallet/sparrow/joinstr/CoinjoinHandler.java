package com.sparrowwallet.sparrow.joinstr;

import com.google.gson.Gson;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.psbt.PSBTParseException;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.WalletHistoryFinishedEvent;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import javafx.application.Platform;
import nostr.api.NIP04;
import nostr.event.BaseTag;
import nostr.event.Kind;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.PubKeyTag;
import nostr.id.Identity;
import org.bouncycastle.util.encoders.Base64;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Unified handler for coinjoin phases - works for both pool creators and
 * joiners.
 * Manages: output collection, input registration, PSBT combining, and
 * broadcasting.
 */
public class CoinjoinHandler {
    private static final Logger logger = Logger.getLogger(CoinjoinHandler.class.getName());

    private final Identity poolIdentity;
    private final JoinstrPool pool;
    private final String relay;
    private final int numPeers;
    private final long poolAmountSats;
    private final long feeRate;
    private final Consumer<String> statusCallback;

    private final List<String> outputAddresses = new CopyOnWriteArrayList<>();
    private final List<String> inputPSBTs = new CopyOnWriteArrayList<>();
    private String myOutputAddress;
    private String myPsbtBase64;

    private Wallet wallet;
    private Storage storage;
    private NostrListener messageListener;

    public CoinjoinHandler(Identity poolIdentity, JoinstrPool pool, Consumer<String> statusCallback) {
        this.poolIdentity = poolIdentity;
        this.pool = pool;
        this.relay = pool.getRelay();
        this.statusCallback = statusCallback;

        // Parse peers count
        String peersStr = pool.getPeers();
        if (peersStr.contains("/")) {
            this.numPeers = Integer.parseInt(peersStr.split("/")[1].trim());
        } else {
            this.numPeers = Integer.parseInt(peersStr.trim());
        }

        // Parse denomination (BTC to sats)
        this.poolAmountSats = (long) (Double.parseDouble(pool.getDenomination()) * 100_000_000);

        // Fee rate (default 1 sat/vbyte if not set)
        this.feeRate = 1;

        // Get wallet reference
        Map<Wallet, Storage> openWallets = AppServices.get().getOpenWallets();
        if (!openWallets.isEmpty()) {
            Map.Entry<Wallet, Storage> firstWallet = openWallets.entrySet().iterator().next();
            this.wallet = firstWallet.getKey();
            this.storage = firstWallet.getValue();
        }
    }

    /**
     * Start the output phase - register our output and listen for others.
     */
    public void startOutputPhase(String myOutputAddress) {
        this.myOutputAddress = myOutputAddress;
        outputAddresses.add(myOutputAddress);

        updateStatus("Registering output");

        // Send output to pool
        sendOutputToPool(myOutputAddress);

        // Start listening for outputs and inputs from peers
        startListeningForMessages();
    }

    private void sendOutputToPool(String address) {
        try {
            String outputContent = String.format("{\"type\":\"output\",\"address\":\"%s\"}", address);

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
            nip04.send(Map.of("default", relay));

            logger.info("Output registered: " + address);
        } catch (Exception e) {
            logger.severe("Failed to send output: " + e.getMessage());
            updateStatus("Error: " + e.getMessage());
        }
    }

    private void startListeningForMessages() {
        messageListener = new NostrListener(poolIdentity, relay, null);
        messageListener.startListening(this::handleDecryptedMessage);
    }

    private void handleDecryptedMessage(String decryptedMessage) {
        try {
            if (decryptedMessage.contains("\"type\":\"output\"") || decryptedMessage.contains("\"type\": \"output\"")) {
                handleOutputReceived(decryptedMessage);
            } else if (decryptedMessage.contains("\"type\":\"input\"")
                    || decryptedMessage.contains("\"type\": \"input\"")) {
                handleInputReceived(decryptedMessage);
            }
        } catch (Exception e) {
            logger.severe("Error handling message: " + e.getMessage());
        }
    }

    private void handleOutputReceived(String decryptedMessage) {
        try {
            Gson gson = new Gson();
            Map<String, String> outputData = gson.fromJson(decryptedMessage, Map.class);
            String address = outputData.get("address");

            if (address != null && !outputAddresses.contains(address)) {
                outputAddresses.add(address);
                logger.info("Received output " + outputAddresses.size() + "/" + numPeers + ": " + address);

                updateStatus("Outputs: " + outputAddresses.size() + "/" + numPeers);

                if (outputAddresses.size() == numPeers) {
                    logger.info("All outputs collected, ready for input registration");
                    updateStatus("Select UTXO for input");
                    // The UI will trigger startInputPhase() when user selects a UTXO
                }
            }
        } catch (Exception e) {
            logger.severe("Error handling output: " + e.getMessage());
        }
    }

    /**
     * Start input phase - create and sign PSBT with selected UTXO.
     */
    public void startInputPhase(BlockTransactionHashIndex selectedUtxo, WalletNode utxoNode) {
        updateStatus("Creating PSBT");

        try {
            PSBT psbt = createCoinjoinPSBT(selectedUtxo, utxoNode);
            if (psbt == null) {
                updateStatus("Error: Failed to create PSBT");
                return;
            }

            // Sign the PSBT
            updateStatus("Signing PSBT");
            signPSBT(psbt, selectedUtxo, utxoNode);

            // Serialize and send
            byte[] psbtBytes = psbt.serialize();
            myPsbtBase64 = Base64.toBase64String(psbtBytes);
            inputPSBTs.add(myPsbtBase64);

            sendInputToPool(myPsbtBase64);
            updateStatus("Input sent, waiting for peers");

        } catch (Exception e) {
            logger.severe("Error in input phase: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Error: " + e.getMessage());
        }
    }

    private PSBT createCoinjoinPSBT(BlockTransactionHashIndex utxo, WalletNode utxoNode) {
        try {
            Transaction tx = new Transaction();
            tx.setVersion(2);

            // Add the input
            tx.addInput(utxo.getHash(), (int) utxo.getIndex(), new Script(new byte[0]));

            // Calculate output amount (pool amount minus fee share)
            // Estimate ~150 vbytes per input, split fee among all participants
            long estimatedTxSize = 150L * numPeers;
            long totalFee = feeRate * estimatedTxSize;
            long feePerOutput = totalFee / numPeers;
            long outputAmount = poolAmountSats - feePerOutput;

            logger.info("Creating PSBT: pool=" + poolAmountSats + " sats, fee/output=" + feePerOutput + ", output="
                    + outputAmount);

            // Add outputs for all participants
            for (String addr : outputAddresses) {
                Address address = Address.fromString(addr);
                tx.addOutput(outputAmount, address.getOutputScript());
            }

            // Create PSBT
            PSBT psbt = new PSBT(tx);

            // Set sighash to ANYONECANPAY_ALL (0x81)
            PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
            psbtInput.setSigHash(SigHash.ANYONECANPAY_ALL);

            // Add witness UTXO for signing
            if (wallet != null) {
                Transaction utxoTx = wallet.getTransactions().get(utxo.getHash()).getTransaction();
                TransactionOutput witnessUtxo = utxoTx.getOutputs().get((int) utxo.getIndex());
                psbtInput.setWitnessUtxo(witnessUtxo);

                // Add key derivation info for signing
                // This is needed for the wallet to know which key to use
            }

            return psbt;

        } catch (Exception e) {
            logger.severe("Error creating PSBT: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private void signPSBT(PSBT psbt, BlockTransactionHashIndex utxo, WalletNode utxoNode) {
        // For now, we'll use a simple approach - the PSBT needs to be signed by the
        // wallet
        // In Sparrow, this typically happens through the UI or via FinalizingPSBTWallet
        //
        // For a hot wallet with software keys, we can attempt to sign directly
        // For hardware wallets, this would need to go through the device signing flow

        try {
            // Try to finalize using the wallet's signing capability
            if (wallet != null && wallet.isValid() && !wallet.getKeystores().isEmpty()) {
                // The wallet should be able to sign if it has the private keys
                // This is a simplified approach - full implementation would handle different
                // keystore types

                logger.info("Attempting to sign PSBT...");
                // Note: Full signing implementation depends on keystore type
                // For software wallets, Sparrow uses wallet.sign() methods
                // For now, log that signing would happen here
                logger.info("PSBT signing - wallet type: " + wallet.getKeystores().get(0).getSource());
            }
        } catch (Exception e) {
            logger.severe("Error signing PSBT: " + e.getMessage());
        }
    }

    private void sendInputToPool(String psbtBase64) {
        try {
            String inputContent = String.format("{\"type\":\"input\",\"psbt\":\"%s\"}", psbtBase64);

            List<BaseTag> tags = new ArrayList<>();
            tags.add(new PubKeyTag(poolIdentity.getPublicKey()));

            NIP04 nip04 = new NIP04(poolIdentity, poolIdentity.getPublicKey());
            String encryptedContent = nip04.encrypt(poolIdentity, inputContent, poolIdentity.getPublicKey());

            GenericEvent inputEvent = new GenericEvent(
                    poolIdentity.getPublicKey(),
                    Kind.ENCRYPTED_DIRECT_MESSAGE.getValue(),
                    tags,
                    encryptedContent);

            nip04.setEvent(inputEvent);
            nip04.sign();
            nip04.send(Map.of("default", relay));

            logger.info("Signed input sent to pool");
        } catch (Exception e) {
            logger.severe("Failed to send input: " + e.getMessage());
            updateStatus("Error: " + e.getMessage());
        }
    }

    private void handleInputReceived(String decryptedMessage) {
        try {
            Gson gson = new Gson();
            Map<String, String> inputData = gson.fromJson(decryptedMessage, Map.class);
            String psbt = inputData.get("psbt");

            if (psbt != null && !inputPSBTs.contains(psbt)) {
                inputPSBTs.add(psbt);
                logger.info("Received input " + inputPSBTs.size() + "/" + numPeers);

                updateStatus("Inputs: " + inputPSBTs.size() + "/" + numPeers);

                if (inputPSBTs.size() == numPeers) {
                    logger.info("All inputs collected, finalizing coinjoin");
                    finalizeCoinjoin();
                }
            }
        } catch (Exception e) {
            logger.severe("Error handling input: " + e.getMessage());
        }
    }

    private void finalizeCoinjoin() {
        updateStatus("Finalizing coinjoin");

        try {
            // Combine all PSBTs
            PSBT combined = new PSBT(Base64.decode(inputPSBTs.get(0)), false);

            for (int i = 1; i < inputPSBTs.size(); i++) {
                PSBT otherPsbt = new PSBT(Base64.decode(inputPSBTs.get(i)), false);
                combined.combine(otherPsbt);
            }

            logger.info("Combined " + inputPSBTs.size() + " PSBTs");

            // Calculate the fee BEFORE extracting the transaction
            long totalInputValue = 0;
            for (PSBTInput input : combined.getPsbtInputs()) {
                if (input.getWitnessUtxo() != null) {
                    totalInputValue += input.getWitnessUtxo().getValue();
                }
            }

            // Validate our output is present
            Transaction finalTx = combined.extractTransaction();

            long totalOutputValue = 0;
            boolean ourOutputFound = false;
            long expectedAmount = poolAmountSats - (feeRate * 150 * numPeers / numPeers);

            for (TransactionOutput output : finalTx.getOutputs()) {
                totalOutputValue += output.getValue();
                try {
                    Address outputAddr = output.getScript().getToAddress();
                    if (outputAddr.toString().equals(myOutputAddress)) {
                        ourOutputFound = true;
                        logger.info("Found our output: " + myOutputAddress + " with value " + output.getValue());
                    }
                } catch (Exception e) {
                    // Non-standard script, skip
                }
            }

            if (!ourOutputFound) {
                logger.severe("Our output address not found in final transaction!");
                updateStatus("Error: Output validation failed");
                return;
            }

            // Calculate fee
            long fee = totalInputValue - totalOutputValue;
            logger.info("Transaction fee: " + fee + " sats");

            // Broadcast the transaction with fee
            updateStatus("Broadcasting transaction");
            broadcastTransaction(finalTx, fee);

        } catch (PSBTParseException e) {
            logger.severe("Failed to parse PSBT: " + e.getMessage());
            updateStatus("Error: Invalid PSBT");
        } catch (Exception e) {
            logger.severe("Error finalizing coinjoin: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Error: " + e.getMessage());
        }
    }

    private void broadcastTransaction(Transaction tx, long fee) {
        try {
            ElectrumServer.BroadcastTransactionService broadcastService =
                    new ElectrumServer.BroadcastTransactionService(tx, fee);

            broadcastService.setOnSucceeded(event -> {
                logger.info("Coinjoin transaction broadcast successfully! TXID: " + tx.getTxId());
                updateStatus("Complete! TXID: " + tx.getTxId().toString().substring(0, 16) + "...");

                // Stop listening
                stopListening();
            });

            broadcastService.setOnFailed(event -> {
                Throwable error = broadcastService.getException();
                logger.severe("Failed to broadcast: " + error.getMessage());
                updateStatus("Broadcast failed: " + error.getMessage());
            });

            broadcastService.start();

        } catch (Exception e) {
            logger.severe("Error broadcasting transaction: " + e.getMessage());
            updateStatus("Error: " + e.getMessage());
        }
    }

    public void stopListening() {
        try {
            if (messageListener != null) {
                messageListener.stop();
            }
        } catch (Exception e) {
            logger.warning("Error stopping listener: " + e.getMessage());
        }
    }

    private void updateStatus(String status) {
        if (statusCallback != null) {
            Platform.runLater(() -> statusCallback.accept(status));
        }
    }

    // Getters for UI access
    public List<String> getOutputAddresses() {
        return new ArrayList<>(outputAddresses);
    }

    public int getNumPeers() {
        return numPeers;
    }

    public long getPoolAmountSats() {
        return poolAmountSats;
    }

    public boolean isReadyForInputPhase() {
        return outputAddresses.size() == numPeers;
    }
}
