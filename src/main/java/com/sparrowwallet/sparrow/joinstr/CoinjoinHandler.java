package com.sparrowwallet.sparrow.joinstr;

import com.google.gson.Gson;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.Script;
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
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import javafx.application.Platform;
import javafx.concurrent.Task;
import nostr.api.NIP04;
import nostr.client.Client;
import nostr.context.impl.DefaultRequestContext;
import nostr.event.BaseTag;
import nostr.event.Kind;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.PubKeyTag;
import nostr.id.Identity;
import com.sparrowwallet.sparrow.net.TorUtils;
import org.bouncycastle.util.encoders.Base64;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private double feeRate;
    private final Consumer<String> statusCallback;

    private final List<String> outputAddresses = new CopyOnWriteArrayList<>();
    private final Map<String, Long> outputTimes = new ConcurrentHashMap<>();
    private final List<String> inputPSBTs = new CopyOnWriteArrayList<>();
    private final Set<String> allInputs = Collections.synchronizedSet(new HashSet<>());
    private String myOutputAddress;
    private String myPsbtBase64;

    private Wallet wallet;
    private Storage storage;
    private NostrListener messageListener;
    private Runnable onReadyForInputCallback;

    private final Object stateLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean finalizing = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean holdingDiscovery = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean completed = false;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();

    public CoinjoinHandler(Identity poolIdentity, JoinstrPool pool, Wallet wallet, Storage storage,
            Consumer<String> statusCallback) {
        this.poolIdentity = poolIdentity;
        this.pool = pool;
        this.relay = pool.getRelay();
        this.statusCallback = statusCallback;
        this.wallet = wallet;
        this.storage = storage;

        this.numPeers = pool.getParsedPeers();

        this.poolAmountSats = CoinjoinMath.denominationToSats(pool.getDenomination());
    }

    public void setFeeRate(double feeRate) {
        this.feeRate = feeRate;
    }

    /**
     * Start the output phase - register our output and listen for others.
     */
    public void startOutputPhase(String myOutputAddress) {
        try {
            Address.fromString(myOutputAddress);
        } catch (Exception e) {
            logger.severe("Own output address is not a valid bitcoin address");
            updateStatus("Error: Invalid address");
            return;
        }

        this.myOutputAddress = myOutputAddress;

        if (holdingDiscovery.compareAndSet(false, true)) {
            CoinjoinActivity.started();
        }
        updateStatus("Output registered");

        scheduleTimeout();

        sendOutputToPool(myOutputAddress);

        startListeningForMessages();
    }

    /**
     * Abort the coinjoin if it has not completed by the pool timeout, so a stalled pool does not
     * hang forever with the selected UTXO locked.
     */
    private void scheduleTimeout() {
        long delaySeconds;
        try {
            delaySeconds = Long.parseLong(pool.getTimeout().trim()) - Instant.now().getEpochSecond();
        } catch (Exception e) {
            delaySeconds = 3600;
        }
        if (delaySeconds <= 0) {
            delaySeconds = 3600;
        }

        timeoutExecutor.schedule(() -> {
            if (!completed) {
                logger.warning("Coinjoin timed out before completion");
                updateStatus("Timed out");
                stopListening();
            }
        }, delaySeconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void sendOutputToPool(String address) {
        executorService.submit(() -> {
            try {
                if (!JoinstrTransport.newCircuit()) {
                    updateStatus("Error: Tor not running");
                    return;
                }

                JoinstrMessage message = JoinstrMessage.of("output");
                message.setAddress(address);
                String outputContent = message.toJson();

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

                {
                    DefaultRequestContext context = new DefaultRequestContext();
                    context.setPrivateKey(poolIdentity.getPrivateKey().getRawData());
                    context.setRelays(Map.of("default", relay));
                    Client.getInstance().connect(context);
                }

                nip04.send(Map.of("default", relay));

                Long createdAt = outputEvent.getCreatedAt();
                recordOutput(address, createdAt == null ? Instant.now().getEpochSecond() : createdAt);

                logger.info("Output registered");
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

    void handleDecryptedMessage(String decryptedMessage) {
        handleDecryptedMessage(decryptedMessage, Instant.now().getEpochSecond());
    }

    void handleDecryptedMessage(String decryptedMessage, long createdAt) {
        try {
            JoinstrMessage message = JoinstrMessage.fromJson(decryptedMessage);
            String type = message.getType();

            if ("output".equals(type)) {
                handleOutputReceived(message, createdAt);
            } else if ("input".equals(type)) {
                handleInputReceived(message);
            }
        } catch (Exception e) {
            logger.severe("Error handling message: " + e.getMessage());
        }
    }

    private void handleOutputReceived(JoinstrMessage message, long createdAt) {
        try {
            String addressStr = message.getAddress();
            if (addressStr == null) {
                return;
            }

            try {
                Address.fromString(addressStr);
            } catch (Exception e) {
                logger.warning("Ignoring an output that is not a valid bitcoin address");
                return;
            }

            synchronized (stateLock) {
                if (outputAddresses.contains(addressStr) || outputAddresses.size() >= numPeers) {
                    return;
                }

                recordOutput(addressStr, createdAt);
                logger.info("Received output " + outputAddresses.size() + "/" + numPeers);

                if (outputAddresses.size() == numPeers) {
                    logger.info("All outputs registered, ready for input registration");
                    updateStatus("Input registration");
                    if (onReadyForInputCallback != null) {
                        FxDispatch.run(onReadyForInputCallback);
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
        // The outpoint alongside the registered outputs is the input to output linkage this
        // coinjoin exists to break, so log shapes rather than values.
        logger.info("Starting input registration");

        String rejection = CoinjoinInput.rejectionReason(coinFacts(selectedUtxo, utxoNode), poolAmountSats,
                CoinjoinMath.outputAmount(poolAmountSats, feeRate, numPeers), dustLimit(), outputAddresses);
        if (rejection != null) {
            logger.warning("Refusing the selected UTXO for this pool");
            FxDispatch.run(() -> {
                com.sparrowwallet.sparrow.AppServices.showErrorDialog("Invalid UTXO", rejection);
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

    /**
     * Record an output and the time its announcement was published.
     *
     * The registration PSBT commits to the outputs in order, so every peer has to arrive at the
     * same order or the signatures do not agree. Peers see announcements in whatever order their
     * own relay poll returns, so ordering by publication time is what makes them converge.
     */
    private void recordOutput(String address, long createdAt) {
        synchronized (stateLock) {
            if (!outputTimes.containsKey(address)) {
                outputTimes.put(address, createdAt);
                outputAddresses.add(address);
            }
        }
        pool.setConnectedPeers(outputAddresses.size());
    }

    /** The registered outputs in the order every peer should build them. */
    List<String> orderedOutputs() {
        List<String> ordered = new ArrayList<>(outputAddresses);
        ordered.sort(Comparator
                .comparingLong((String address) -> outputTimes.getOrDefault(address, 0L))
                .thenComparing(Comparator.naturalOrder()));
        return ordered;
    }

    /** Read the facts CoinjoinInput needs off the wallet. */
    CoinjoinInput.Coin coinFacts(BlockTransactionHashIndex utxo, WalletNode utxoNode) {
        boolean confirmed = utxo.getHeight() > 0;
        boolean spendable = wallet == null || wallet.getSpendableUtxos().containsKey(utxo);
        String address = null;
        try {
            if (utxoNode != null && utxoNode.getAddress() != null) {
                address = utxoNode.getAddress().toString();
            }
        } catch (Exception e) {
            // an address that cannot be derived cannot be compared against the pool's outputs
        }

        return new CoinjoinInput.Coin(utxo.getValue(), confirmed, spendable, address);
    }

    /**
     * The dust threshold every output in this pool must clear. Peers can register any address
     * type, so the floor follows the largest one present.
     */
    private long dustLimit() {
        long limit = 0;
        for (String address : outputAddresses) {
            try {
                limit = Math.max(limit, com.sparrowwallet.sparrow.wallet.PaymentController
                        .getRecipientDustThreshold(Address.fromString(address)));
            } catch (Exception e) {
                // an unparseable address never reaches the output list, but do not let one stop
                // the check for the others
            }
        }
        return limit;
    }

    private PSBT createCoinjoinPSBT(BlockTransactionHashIndex utxo, WalletNode utxoNode) {
        try {
            Transaction tx = new Transaction();
            tx.setVersion(2);

            tx.addInput(utxo.getHash(), (int) utxo.getIndex(), new Script(new byte[0]));

            long feePerOutput = CoinjoinMath.feePerOutput(feeRate, numPeers);
            long outputAmount = CoinjoinMath.outputAmount(poolAmountSats, feeRate, numPeers);

            logger.info("Creating PSBT: pool=" + poolAmountSats + " sats, fee/output=" + feePerOutput + ", output="
                    + outputAmount);

            List<String> sortedOutputs = orderedOutputs();
            logger.info("Ordered " + sortedOutputs.size() + " outputs by announcement time");

            for (String addr : sortedOutputs) {
                Address address = Address.fromString(addr);
                tx.addOutput(outputAmount, address.getOutputScript());
            }

            if (CoinjoinMath.isSpendableAlone(utxo.getValue(), outputAmount, sortedOutputs.size())) {
                logger.severe("Refusing to sign a registration PSBT that is spendable on its own: "
                        + sortedOutputs.size() + " outputs of " + outputAmount
                        + " sats do not exceed the input of " + utxo.getValue() + " sats");
                return null;
            }

            PSBT psbt = new PSBT(tx);

            PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
            psbtInput.setSigHash(CoinjoinMath.INPUT_SIGHASH);

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
        long expectedOutputAmount = CoinjoinMath.outputAmount(poolAmountSats, feeRate, numPeers);

        List<CoinjoinMath.OutputView> views = new ArrayList<>();
        for (TransactionOutput output : tx.getOutputs()) {
            try {
                String addrStr = output.getScript().getToAddress().toString();
                views.add(new CoinjoinMath.OutputView(addrStr, output.getValue()));
            } catch (Exception e) {
                logger.warning("Could not parse output address: " + e.getMessage());
            }
        }

        boolean valid = CoinjoinMath.validateOutputs(views, expectedAddresses, expectedOutputAmount);
        if (!valid) {
            logger.severe("Output validation failed against expected amount " + expectedOutputAmount);
        }
        return valid;
    }

    private void signPSBT(PSBT psbt, BlockTransactionHashIndex utxo, WalletNode utxoNode, Wallet signingWallet) {
        try {
            com.sparrowwallet.drongo.wallet.Keystore keystore = signingWallet.getKeystores().get(0);
            if (!keystore.hasPrivateKey()) {
                logger.warning("Hardware wallet detected - signing not yet implemented for hardware wallets");
                updateStatus("Error: Check logs");
                return;
            }

            logger.info("Signing the registration PSBT");

            com.sparrowwallet.drongo.crypto.ECKey privateKey = keystore.getKey(utxoNode);

            if (privateKey == null || !privateKey.hasPrivKey()) {
                logger.severe("Could not get the private key for the selected UTXO");
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
            if (!JoinstrTransport.newCircuit()) {
                updateStatus("Error: Tor not running");
                return;
            }

            JoinstrMessage message = JoinstrMessage.of("input");
            message.setPsbt(psbtBase64);
            String inputContent = message.toJson();

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

            {
                DefaultRequestContext context = new DefaultRequestContext();
                context.setPrivateKey(poolIdentity.getPrivateKey().getRawData());
                context.setRelays(Map.of("default", relay));
                Client.getInstance().connect(context);
            }

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
            if (psbtBase64 == null) {
                return;
            }

            synchronized (stateLock) {
                if (inputPSBTs.contains(psbtBase64) || inputPSBTs.size() >= numPeers) {
                    return;
                }

                PSBT psbt = new PSBT(Base64.decode(psbtBase64), false);

                for (PSBTInput input : psbt.getPsbtInputs()) {
                    String outpoint = input.getInput().getOutpoint().toString();
                    if (allInputs.contains(outpoint)) {
                        logger.warning("Rejecting a PSBT that reuses an already registered input");
                        return;
                    }
                }

                long expectedOutputAmount = CoinjoinMath.outputAmount(poolAmountSats, feeRate, numPeers);

                String rejection = RegistrationPsbt.rejectionReason(psbt, outputAddresses,
                        expectedOutputAmount, numPeers);
                if (rejection != null) {
                    logger.warning("Rejecting a peer registration: " + rejection);
                    return;
                }

                inputPSBTs.add(psbtBase64);
                for (PSBTInput input : psbt.getPsbtInputs()) {
                    allInputs.add(input.getInput().getOutpoint().toString());
                }

                logger.info("Received valid input " + inputPSBTs.size() + "/" + numPeers);

                if (inputPSBTs.size() == numPeers && finalizing.compareAndSet(false, true)) {
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

            long expectedOutputAmount = CoinjoinMath.outputAmount(poolAmountSats, feeRate, numPeers);
            boolean enforceFeeBounds = com.sparrowwallet.drongo.Network.get()
                    != com.sparrowwallet.drongo.Network.REGTEST;

            String rejection = CoinjoinTransaction.rejectionReason(combinedPsbt, outputAddresses,
                    expectedOutputAmount, numPeers, enforceFeeBounds);
            if (rejection != null) {
                logger.severe("Refusing to broadcast the coinjoin: " + rejection);
                updateStatus("Error: Check logs");
                return;
            }

            Transaction finalTx = combinedPsbt.extractTransaction();
            long fee = CoinjoinTransaction.fee(combinedPsbt);
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
                try {
                    JoinstrHistoryEntry entry = new JoinstrHistoryEntry(tx.getTxId().toString(), relay, poolAmountSats, Instant.now().getEpochSecond());
                    ArrayList<JoinstrHistoryEntry> history = Config.get().getHistoryStore();
                    history.add(entry);
                    Config.get().setHistoryStore(history);
                    JoinstrHistoryEntry.saveHistoryFile(Storage.getJoinstrHistoryFile().getPath());
                } catch (Exception e) {
                    logger.warning("Failed to save history: " + e.getMessage());
                }
                completed = true;
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
        // idempotent: a coinjoin that both completes and later times out must not release the
        // hold twice, and one that never starts must not release a hold it never took
        if (holdingDiscovery.compareAndSet(true, false)) {
            CoinjoinActivity.finished();
        }
        try {
            if (messageListener != null) {
                messageListener.close();
            }
            timeoutExecutor.shutdownNow();
            executorService.shutdown();
        } catch (Exception e) {
            logger.warning("Error stopping listener: " + e.getMessage());
        }
    }

    private void updateStatus(String status) {
        if (statusCallback != null) {
            FxDispatch.run(() -> statusCallback.accept(status));
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
