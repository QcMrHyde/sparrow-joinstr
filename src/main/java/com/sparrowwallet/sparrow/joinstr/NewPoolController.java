package com.sparrowwallet.sparrow.joinstr;

import static com.sparrowwallet.sparrow.AppServices.showSuccessDialog;
import static com.sparrowwallet.sparrow.wallet.PaymentController.getRecipientDustThreshold;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Payment;
import com.sparrowwallet.drongo.wallet.PresetUtxoSelector;
import com.sparrowwallet.drongo.wallet.TxoFilter;
import com.sparrowwallet.drongo.wallet.UtxoSelector;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.drongo.wallet.WalletTransaction;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javafx.fxml.FXML;
import javafx.scene.control.TextField;

import nostr.event.impl.GenericEvent;
import nostr.id.Identity;

public class NewPoolController extends JoinstrFormController {

    private static final Logger logger = Logger.getLogger(NostrListener.class.getName());
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
                if (peersValue <= 2) {
                    showError("Number of peers must be greater than 2");
                    return;
                }
            } catch (NumberFormatException e) {
                showError("Invalid number of peers format");
                return;
            }

            Address bitcoinAddress;
            Wallet wallet;
            JoinstrPool pool = null;

            try {
                Map<Wallet, Storage> openWallets = AppServices.get().getOpenWallets();
                if (openWallets.isEmpty()) {
                    throw new Exception("No wallet found. Please open a wallet in Sparrow first.");
                }

                Map.Entry<Wallet, Storage> firstWallet = openWallets.entrySet().iterator().next();
                wallet = firstWallet.getKey();
                Storage storage = firstWallet.getValue();
                bitcoinAddress = NostrPublisher.getNewReceiveAddress(storage, wallet);

                double recipientDustThreshold = (double) getRecipientDustThreshold(bitcoinAddress) / 100000000;
                if (Double.parseDouble(denomination) <= recipientDustThreshold) {
                    throw new Exception("Denomination must be greater than recipient dust threshold (" + recipientDustThreshold + ")");
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

                pool = new JoinstrPool(joinstrEvent.relay, joinstrEvent.public_key, joinstrEvent.denomination, joinstrEvent.peers, joinstrEvent.timeout, poolPrivateKey);

                getJoinstrController().setSelectedPool(pool);
                getJoinstrController().setJoinstrDisplay(JoinstrDisplay.MY_POOLS);

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
                            "\n\nPool data saved in file:" +
                            "\n\"" + Storage.getJoinstrPoolsFile().getPath() + "\""
            );

            UtxoCircleDialog dialog = new UtxoCircleDialog(wallet);
            dialog.showAndWait();

            Set<BlockTransactionHashIndex> selectedUTXOs = dialog.getSelectedUtxos();
            if(selectedUTXOs != null) {

                ArrayList<Payment> payments = new ArrayList<>();
                long dustThreshold = getRecipientDustThreshold(bitcoinAddress);
                long totalAmount = selectedUTXOs.stream().mapToLong(BlockTransactionHashIndex::getValue).sum();
                long denominationSats = (long)(Double.parseDouble(denomination) * 100000000);
                while(totalAmount > dustThreshold && totalAmount >= denominationSats) {
                    Payment payment = new Payment(bitcoinAddress, "joinstr-" + event.getId(), denominationSats, true);
                    payments.add(payment);
                    totalAmount = totalAmount - denominationSats;
                }

                Set<WalletNode> excludedChangeNodes = new HashSet<>();
                List<TxoFilter> txoFilters = new ArrayList<>();
                List<byte[]> opReturns = new ArrayList<>();

                double feeRate = 1;
                double longTermFeeRate = 1;
                Long userFee = 0L;

                Integer currentBlockHeight = AppServices.getCurrentBlockHeight();
                boolean groupByAddress = Config.get().isGroupByAddress();
                boolean includeMempoolOutputs = Config.get().isIncludeMempoolOutputs();

                List<UtxoSelector> utxoSelectors = List.of(new PresetUtxoSelector(selectedUTXOs, true, false));
                wallet.setScriptType(ScriptType.P2WPKH);

                WalletTransaction walletTransaction = wallet.createWalletTransaction(utxoSelectors, txoFilters, payments, opReturns, excludedChangeNodes, feeRate, longTermFeeRate, userFee, currentBlockHeight, groupByAddress, includeMempoolOutputs);
                PSBT psbt = new PSBT(walletTransaction);

                GenericEvent psbtEvent = NostrPublisher.publishPSBT(psbt);

                if(psbtEvent.getId() != null && pool != null) {

                    pool.setPsbt(psbt);

                    showSuccessDialog(
                            "PSBT published",
                            "PSBT published successfully!\nEvent ID: " + psbtEvent.getId() +
                                    "\nPSBT: " + psbt.toString()
                    );

                } else {
                    showError("An error occurred while publishing the PSBT on Nostr.");
                }
            }

            if(pool != null)
                updatePoolStore(pool);


        } catch (Exception e) {
            showError("An error occurred: " + e.getMessage());
        }
    }

    private void updatePoolStore(JoinstrPool pool) throws IOException {

        ArrayList<JoinstrPool> pools = Config.get().getPoolStore();

        pools.removeIf(p -> p.getJoinstrIdentity() == pool.getJoinstrIdentity());
        pools.add(pool);
        Config.get().setPoolStore(pools);

        JoinstrPool.savePoolsFile(Storage.getJoinstrPoolsFile().getPath());

    }

    public static void shareCredentials(Identity poolIdentity, String relayUrl, Map<String, String> poolCredentials){

        NostrListener listener = new NostrListener(poolIdentity, relayUrl, poolCredentials);

        listener.startListening(decryptedMessage -> {
            logger.info("Received message: " + decryptedMessage);
        });
    }
}