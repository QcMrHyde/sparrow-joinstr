package com.sparrowwallet.sparrow.joinstr;

import com.google.gson.Gson;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionInput;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.psbt.PSBTParseException;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import javafx.application.Platform;
import javafx.concurrent.Task;
import nostr.api.NIP04;
import nostr.event.BaseTag;
import nostr.event.Kind;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.PubKeyTag;
import nostr.id.Identity;
import org.bouncycastle.util.encoders.Base64;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class CoinjoinHandler {
    private static final Logger logger = Logger.getLogger(CoinjoinHandler.class.getName());

    private final Identity poolIdentity;
    private final JoinstrPool pool;
    private final String relay;
    private final int numPeers;
    private final long poolAmountSats;
    private long feeRate;
    private final Consumer<String> statusCallback;

    private final List<String> outputAddresses = new CopyOnWriteArrayList<>();
    private final List<String> inputPSBTs = new CopyOnWriteArrayList<>();
    private final Set<String> allInputs = Collections.synchronizedSet(new HashSet<>());
    private String myOutputAddress;
    private String myPsbtBase64;

    private Wallet wallet;
    private Storage storage;
    private NostrListener messageListener;
    private Runnable onReadyForInputCallback;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public CoinjoinHandler(Identity poolIdentity, JoinstrPool pool, Wallet wallet, Storage storage,
            Consumer<String> statusCallback) {
        this.poolIdentity = poolIdentity;
        this.pool = pool;
        this.relay = pool.getRelay();
        this.statusCallback = statusCallback;
        this.wallet = wallet;
        this.storage = storage;

        this.numPeers = pool.getParsedPeers();

        String denomStr = pool.getDenomination().replace(" BTC", "").replace("BTC", "").trim();
        this.poolAmountSats = (long) (Double.parseDouble(denomStr) * 100_000_000);
    }

    public void setFeeRate(long feeRate) {
        this.feeRate = feeRate;
    }

    /**
     * Start the output phase - register our output and listen for others.
     */
    public void startOutputPhase(String myOutputAddress) {
        try {
            Address.fromString(myOutputAddress);
        } catch (Exception e) {
            logger.severe("Invalid address: " + myOutputAddress);
            updateStatus("Error: Invalid address");
            return;
        }

        this.myOutputAddress = myOutputAddress;
        outputAddresses.add(myOutputAddress);
        pool.setConnectedPeers(outputAddresses.size());

        updateStatus("Output registered");

        sendOutputToPool(myOutputAddress);

        startListeningForMessages();
    }

    private void sendOutputToPool(String address) {
        executorService.submit(() -> {
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
                updateStatus("Error: Check logs");
            }
        });
    }

    private void startListeningForMessages() {
        messageListener = new NostrListener(poolIdentity, relay, null);
        messageListener.startListening(this::handleDecryptedMessage);
    }

    private void handleDecryptedMessage(String decryptedMessage) {
        try {
            JoinstrMessage message = JoinstrMessage.fromJson(decryptedMessage);
            String type = message.getType();

            if ("output".equals(type)) {
                handleOutputReceived(message);
            } else if ("input".equals(type)) {
                handleInputReceived(message);
            }
        } catch (Exception e) {
            logger.severe("Error handling message: " + e.getMessage());
        }
    }

    private void handleOutputReceived(JoinstrMessage message) {
        try {
            String addressStr = message.getAddress();

            if (addressStr != null && !outputAddresses.contains(addressStr)) {
                try {
                    Address.fromString(addressStr);
                } catch (Exception e) {
                    logger.warning("Received invalid output address: " + addressStr);
                    return;
                }

                outputAddresses.add(addressStr);
                pool.setConnectedPeers(outputAddresses.size());
                logger.info("Received output " + outputAddresses.size() + "/" + numPeers + ": " + addressStr);

                if (outputAddresses.size() == numPeers) {
                    logger.info("All outputs registered, ready for input registration");
                    updateStatus("Input registration");
                    if (onReadyForInputCallback != null) {
                        Platform.runLater(onReadyForInputCallback);
                    }
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
        logger.info("UTXO: " + selectedUtxo.getHash() + ":" + selectedUtxo.getIndex() +
                ", value=" + selectedUtxo.getValue() + " sats");

        long value = selectedUtxo.getValue();
        long minAllowed = poolAmountSats + 500;
        long maxAllowed = poolAmountSats + 5000;
        if (value < minAllowed || value > maxAllowed) {
            Platform.runLater(() -> {
                com.sparrowwallet.sparrow.AppServices.showErrorDialog("Invalid UTXO",
                        "The selected UTXO has a value of " + value + " sats, which is outside the allowed range of "
                                + minAllowed + " to " + maxAllowed + " sats.\n\nPlease select a different UTXO.");
                if (onReadyForInputCallback != null) {
                    onReadyForInputCallback.run();
                }
            });
            return;
        }

        com.sparrowwallet.drongo.SecureString password = null;
        if (wallet.isEncrypted()) {
            com.sparrowwallet.sparrow.control.WalletPasswordDialog dlg = new com.sparrowwallet.sparrow.control.WalletPasswordDialog(
                    wallet.getMasterName(),
                    com.sparrowwallet.sparrow.control.WalletPasswordDialog.PasswordRequirement.LOAD);
            Optional<com.sparrowwallet.drongo.SecureString> optPassword = dlg.showAndWait();
            if (optPassword.isPresent()) {
                password = optPassword.get();
            } else {
                updateStatus("Error: Check logs");
                logger.severe("Password required for encrypted wallet");
                return;
            }
        }
        final com.sparrowwallet.drongo.SecureString finalPassword = password;

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Wallet signingWallet = wallet;
                if (wallet.isEncrypted() && finalPassword != null) {
                    signingWallet = wallet.copy();
                    signingWallet.decrypt(finalPassword);
                }

                try {
                    PSBT psbt = createCoinjoinPSBT(selectedUtxo, utxoNode);
                    if (psbt == null) {
                        updateStatus("Error: Check logs");
                        return null;
                    }

                    if (!validateOutputs(psbt.getTransaction())) {
                        updateStatus("Error: Check logs");
                        return null;
                    }

                    signPSBT(psbt, selectedUtxo, utxoNode, signingWallet);

                    PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
                    logger.info("PSBT after signing - isSigned: " + psbtInput.isSigned() +
                            ", isFinalized: " + psbtInput.isFinalized() +
                            ", partialSigs: "
                            + (psbtInput.getPartialSignatures() != null ? psbtInput.getPartialSignatures().size() : 0));

                    if (!psbtInput.isSigned() && !psbtInput.isFinalized()) {
                        logger.severe("PSBT signing failed - no signatures present");
                        updateStatus("Error: Check logs");
                        return null;
                    }

                    byte[] psbtBytes = psbt.serialize();
                    myPsbtBase64 = Base64.toBase64String(psbtBytes);
                    inputPSBTs.add(myPsbtBase64);

                    logger.info("Sending signed PSBT to pool, size: " + psbtBytes.length + " bytes");
                    sendInputToPool(myPsbtBase64);

                } finally {
                    if (signingWallet != wallet) {
                        signingWallet.clearPrivate();
                    }
                }
                return null;
            }
        };

        executorService.submit(task);
    }

    private PSBT createCoinjoinPSBT(BlockTransactionHashIndex utxo, WalletNode utxoNode) {
        try {
            Transaction tx = new Transaction();
            tx.setVersion(2);

            tx.addInput(utxo.getHash(), (int) utxo.getIndex(), new Script(new byte[0]));

            long estimatedTxSize = 150L * numPeers;
            long totalFee = feeRate * estimatedTxSize;
            long feePerOutput = totalFee / numPeers;
            long outputAmount = poolAmountSats - feePerOutput;

            logger.info("Creating PSBT: pool=" + poolAmountSats + " sats, fee/output=" + feePerOutput + ", output="
                    + outputAmount);

            List<String> sortedOutputs = new ArrayList<>(outputAddresses);
            Collections.sort(sortedOutputs);
            logger.info("Sorted " + sortedOutputs.size() + " output addresses for deterministic ordering");

            for (String addr : sortedOutputs) {
                Address address = Address.fromString(addr);
                tx.addOutput(outputAmount, address.getOutputScript());
            }

            PSBT psbt = new PSBT(tx);

            PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
            psbtInput.setSigHash(SigHash.ANYONECANPAY_ALL);

            if (wallet != null) {
                Transaction utxoTx = wallet.getTransactions().get(utxo.getHash()).getTransaction();
                TransactionOutput witnessUtxo = utxoTx.getOutputs().get((int) utxo.getIndex());
                psbtInput.setWitnessUtxo(witnessUtxo);
                logger.info("PSBT created with witness UTXO");
            }

            return psbt;

        } catch (Exception e) {
            logger.severe("Error creating PSBT: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private boolean validateOutputs(Transaction tx) {
        return validateOutputs(tx, List.of(myOutputAddress));
    }

    private boolean validateOutputs(Transaction tx, List<String> expectedAddresses) {
        long estimatedTxSize = 150L * numPeers;
        long totalFee = feeRate * estimatedTxSize;
        long feePerOutput = totalFee / numPeers;
        long expectedOutputAmount = poolAmountSats - feePerOutput;

        Set<String> foundAddresses = new HashSet<>();

        for (TransactionOutput output : tx.getOutputs()) {
            try {
                Address outputAddr = output.getScript().getToAddress();
                String addrStr = outputAddr.toString();
                if (expectedAddresses.contains(addrStr)) {
                    if (output.getValue() != expectedOutputAmount) {
                        logger.severe(
                                "Output " + addrStr + " has incorrect amount: " + output.getValue() + " vs expected "
                                        + expectedOutputAmount);
                        return false;
                    }
                    foundAddresses.add(addrStr);
                } else {
                    logger.warning("Unrecognized output address in transaction: " + addrStr);
                    return false;
                }
            } catch (Exception e) {
                logger.warning("Could not parse output address: " + e.getMessage());
            }
        }

        if (foundAddresses.size() != expectedAddresses.size()) {
            logger.severe("Missing output addresses. Found " + foundAddresses.size() + " vs expected "
                    + expectedAddresses.size());
            return false;
        }

        return true;
    }

    private void signPSBT(PSBT psbt, BlockTransactionHashIndex utxo, WalletNode utxoNode, Wallet signingWallet) {
        try {
            com.sparrowwallet.drongo.wallet.Keystore keystore = signingWallet.getKeystores().get(0);
            if (!keystore.hasPrivateKey()) {
                logger.warning("Hardware wallet detected - signing not yet implemented for hardware wallets");
                updateStatus("Error: Check logs");
                return;
            }

            logger.info("Signing PSBT with : " + utxoNode.getDerivationPath());

            com.sparrowwallet.drongo.crypto.ECKey privateKey = keystore.getKey(utxoNode);

            if (privateKey == null || !privateKey.hasPrivKey()) {
                logger.severe("Could not get private key for: " + utxoNode.getDerivationPath());
                updateStatus("Error: Check logs");
                return;
            }

            logger.info("Got private key, signing PSBT input...");

            PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
            psbtInput.sign(privateKey);

            logger.info("After signing - isSigned: " + psbtInput.isSigned() +
                    ", isFinalized: " + psbtInput.isFinalized() +
                    ", partialSigs: "
                    + (psbtInput.getPartialSignatures() != null ? psbtInput.getPartialSignatures().size() : 0) +
                    ", hasFinalWitness: " + (psbtInput.getFinalScriptWitness() != null));

            if (psbtInput.isSigned()) {
                logger.info("PSBT signed successfully, now creating witness...");
                if (!psbtInput.getPartialSignatures().isEmpty()) {
                    try {
                        var sigEntry = psbtInput.getPartialSignatures().entrySet().iterator().next();
                        com.sparrowwallet.drongo.crypto.ECKey pubKey = sigEntry.getKey();
                        com.sparrowwallet.drongo.protocol.TransactionSignature sig = sigEntry.getValue();

                        com.sparrowwallet.drongo.protocol.TransactionWitness witness = new com.sparrowwallet.drongo.protocol.TransactionWitness(
                                psbt.getTransaction(), pubKey, sig);

                        psbtInput.setFinalScriptWitness(witness);

                        logger.info("Created finalScriptWitness - isFinalized: " + psbtInput.isFinalized() +
                                ", hasFinalWitness: " + (psbtInput.getFinalScriptWitness() != null) +
                                ", pushCount: " + witness.getPushCount());
                    } catch (Exception e) {
                        logger.warning("Could not create witness: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            } else {
                logger.warning("PSBT signing may not have completed - no signatures found");
            }
        } catch (Exception e) {
            logger.severe("Error signing PSBT: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Error: Check logs");
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
            updateStatus("Error: Check logs");
        }
    }

    private void handleInputReceived(JoinstrMessage message) {
        try {
            String psbtBase64 = message.getPsbt();

            if (psbtBase64 != null && !inputPSBTs.contains(psbtBase64)) {
                PSBT psbt = new PSBT(Base64.decode(psbtBase64), false);

                for (PSBTInput input : psbt.getPsbtInputs()) {
                    String outpoint = input.getOutpoint().toString();
                    if (allInputs.contains(outpoint)) {
                        logger.warning("Rejecting duplicate input: " + outpoint);
                        return;
                    }
                }

                Transaction tx = psbt.getTransaction();
                for (TransactionOutput output : tx.getOutputs()) {
                    try {
                        Address outputAddr = output.getScript().getToAddress();
                        if (!outputAddresses.contains(outputAddr.toString())) {
                            logger.warning("Rejecting PSBT with output address: " + outputAddr);
                            return;
                        }
                    } catch (Exception e) {
                    }
                }

                inputPSBTs.add(psbtBase64);
                for (PSBTInput input : psbt.getPsbtInputs()) {
                    allInputs.add(input.getOutpoint().toString());
                }

                logger.info("Received valid input " + inputPSBTs.size() + "/" + numPeers);

                if (inputPSBTs.size() == numPeers) {
                    logger.info("All inputs registered, finalizing coinjoin");

                    // Run finalization in background
                    Task<Void> finalizeTask = new Task<>() {
                        @Override
                        protected Void call() throws Exception {
                            finalizeCoinjoin();
                            return null;
                        }
                    };
                    executorService.submit(finalizeTask);
                }
            }
        } catch (Exception e) {
            logger.severe("Error handling input: " + e.getMessage());
        }
    }

    private void finalizeCoinjoin() {
        logger.info("PSBTs: " + inputPSBTs.size());
        updateStatus("Finalize");

        try {

            logger.info("Merging " + inputPSBTs.size() + " PSBTs for coinjoin");

            List<PSBT> psbts = new ArrayList<>();
            for (String psbtBase64 : inputPSBTs) {
                PSBT psbt = new PSBT(Base64.decode(psbtBase64), false);
                psbts.add(psbt);
                logger.info("Parsed PSBT with " + psbt.getPsbtInputs().size() + " inputs, " +
                        psbt.getTransaction().getOutputs().size() + " outputs");
            }

            Transaction combinedTx = new Transaction();
            combinedTx.setVersion(2);

            for (PSBT psbt : psbts) {
                Transaction tx = psbt.getTransaction();
                for (TransactionInput input : tx.getInputs()) {
                    combinedTx.addInput(input.getOutpoint().getHash(), (int) input.getOutpoint().getIndex(),
                            input.getScriptSig());
                }
            }

            Transaction firstTx = psbts.get(0).getTransaction();
            for (TransactionOutput output : firstTx.getOutputs()) {
                combinedTx.addOutput(output.getValue(), output.getScript());
            }

            logger.info("Combined transaction: " + combinedTx.getInputs().size() + " inputs, " +
                    combinedTx.getOutputs().size() + " outputs");

            PSBT combinedPsbt = new PSBT(combinedTx);

            int inputIndex = 0;
            for (PSBT psbt : psbts) {
                PSBTInput originalInput = psbt.getPsbtInputs().get(0);
                PSBTInput combinedInput = combinedPsbt.getPsbtInputs().get(inputIndex);

                logger.info("Input " + inputIndex + " state: isSigned=" + originalInput.isSigned() +
                        ", isFinalized=" + originalInput.isFinalized() +
                        ", hasWitnessUtxo=" + (originalInput.getWitnessUtxo() != null) +
                        ", hasFinalWitness=" + (originalInput.getFinalScriptWitness() != null) +
                        ", hasFinalSig=" + (originalInput.getFinalScriptSig() != null) +
                        ", partialSigs="
                        + (originalInput.getPartialSignatures() != null ? originalInput.getPartialSignatures().size()
                                : 0));

                if (originalInput.getWitnessUtxo() != null) {
                    combinedInput.setWitnessUtxo(originalInput.getWitnessUtxo());
                }

                if (originalInput.getFinalScriptWitness() != null) {
                    combinedInput.setFinalScriptWitness(originalInput.getFinalScriptWitness());
                    logger.info("Copied final script witness for input " + inputIndex);
                }

                if (originalInput.getFinalScriptSig() != null) {
                    combinedInput.setFinalScriptSig(originalInput.getFinalScriptSig());
                    logger.info("Copied final script sig for input " + inputIndex);
                }

                if (originalInput.getPartialSignatures() != null && !originalInput.getPartialSignatures().isEmpty()) {
                    for (var entry : originalInput.getPartialSignatures().entrySet()) {
                        combinedInput.getPartialSignatures().put(entry.getKey(), entry.getValue());
                    }
                    logger.info("Copied " + originalInput.getPartialSignatures().size() +
                            " partial signatures for input " + inputIndex);
                }

                if (originalInput.getSigHash() != null) {
                    combinedInput.setSigHash(originalInput.getSigHash());
                }

                inputIndex++;
            }

            for (int i = 0; i < combinedPsbt.getPsbtInputs().size(); i++) {
                PSBTInput input = combinedPsbt.getPsbtInputs().get(i);
                logger.info("Combined input " + i + " before finalize: isSigned=" + input.isSigned() +
                        ", isFinalized=" + input.isFinalized() +
                        ", hasFinalWitness=" + (input.getFinalScriptWitness() != null) +
                        ", partialSigs="
                        + (input.getPartialSignatures() != null ? input.getPartialSignatures().size() : 0));
            }

            for (int i = 0; i < combinedPsbt.getPsbtInputs().size(); i++) {
                PSBTInput input = combinedPsbt.getPsbtInputs().get(i);

                if (input.getFinalScriptWitness() != null) {
                    logger.info("Input " + i + " already has finalScriptWitness");
                    continue;
                }

                if (input.getPartialSignatures() != null && !input.getPartialSignatures().isEmpty()) {
                    try {
                        var sigEntry = input.getPartialSignatures().entrySet().iterator().next();
                        com.sparrowwallet.drongo.crypto.ECKey pubKey = sigEntry.getKey();
                        com.sparrowwallet.drongo.protocol.TransactionSignature sig = sigEntry.getValue();

                        com.sparrowwallet.drongo.protocol.TransactionWitness witness = new com.sparrowwallet.drongo.protocol.TransactionWitness(
                                combinedPsbt.getTransaction(), pubKey, sig);

                        input.setFinalScriptWitness(witness);
                        logger.info("Created witness for input " + i + ", pushCount: " + witness.getPushCount());
                    } catch (Exception e) {
                        logger.warning("Could not create witness for input " + i + ": " + e.getMessage());
                    }
                } else {
                    logger.warning("Input " + i + " has no partial signatures to finalize!");
                }
            }

            for (int i = 0; i < combinedPsbt.getPsbtInputs().size(); i++) {
                PSBTInput input = combinedPsbt.getPsbtInputs().get(i);
                logger.info("Combined input " + i + " after finalize: isFinalized=" + input.isFinalized() +
                        ", hasFinalWitness=" + (input.getFinalScriptWitness() != null));
            }

            long totalInputValue = 0;
            for (PSBTInput input : combinedPsbt.getPsbtInputs()) {
                if (input.getWitnessUtxo() != null) {
                    totalInputValue += input.getWitnessUtxo().getValue();
                }
            }

            Transaction finalTx = combinedPsbt.extractTransaction();

            if (!validateOutputs(finalTx, outputAddresses)) {
                updateStatus("Error: Check logs");
                return;
            }

            long totalOutputValue = 0;
            for (TransactionOutput output : finalTx.getOutputs()) {
                totalOutputValue += output.getValue();
            }

            long fee = totalInputValue - totalOutputValue;
            logger.info("Final transaction: txid=" + finalTx.getTxId() + ", fee=" + fee + " sats");

            updateStatus("broadcast");
            broadcastTransaction(finalTx, fee);

        } catch (PSBTParseException e) {
            logger.severe("Failed to parse PSBT: " + e.getMessage());
            updateStatus("Error: Check logs");
        } catch (Exception e) {
            logger.severe("Error finalizing coinjoin: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Error: Check logs");
        }
    }

    private void broadcastTransaction(Transaction tx, long fee) {
        try {
            ElectrumServer.BroadcastTransactionService broadcastService = new ElectrumServer.BroadcastTransactionService(
                    tx, fee);

            broadcastService.setOnSucceeded(event -> {
                logger.info("Coinjoin transaction broadcast successfully! TXID: " + tx.getTxId());
                updateStatus("Complete");
                pool.setStatus("Complete");

                stopListening();
            });

            broadcastService.setOnFailed(event -> {
                Throwable error = broadcastService.getException();
                logger.severe("Failed to broadcast: " + error.getMessage());
                updateStatus("Error: Check logs");
            });

            broadcastService.start();

        } catch (Exception e) {
            logger.severe("Error broadcasting transaction: " + e.getMessage());
            updateStatus("Error: Check logs");
        }
    }

    public void stopListening() {
        try {
            if (messageListener != null) {
                messageListener.close();
            }
            executorService.shutdown();
        } catch (Exception e) {
            logger.warning("Error stopping listener: " + e.getMessage());
        }
    }

    private void updateStatus(String status) {
        if (statusCallback != null) {
            Platform.runLater(() -> statusCallback.accept(status));
        }
    }

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

    public void setOnReadyForInputCallback(Runnable callback) {
        this.onReadyForInputCallback = callback;
    }

    public Wallet getWallet() {
        return wallet;
    }
}
