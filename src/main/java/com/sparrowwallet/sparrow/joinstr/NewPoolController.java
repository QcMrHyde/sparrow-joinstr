package com.sparrowwallet.sparrow.joinstr;

import static com.sparrowwallet.sparrow.AppServices.showSuccessDialog;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.wallet.PaymentController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import com.sparrowwallet.drongo.BitcoinUnit;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.BnBUtxoSelector;
import com.sparrowwallet.drongo.wallet.CoinbaseTxoFilter;
import com.sparrowwallet.drongo.wallet.FrozenTxoFilter;
import com.sparrowwallet.drongo.wallet.InsufficientFundsException;
import com.sparrowwallet.drongo.wallet.KnapsackUtxoSelector;
import com.sparrowwallet.drongo.wallet.Payment;
import com.sparrowwallet.drongo.wallet.SpentTxoFilter;
import com.sparrowwallet.drongo.wallet.TxoFilter;
import com.sparrowwallet.drongo.wallet.UtxoSelector;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.drongo.wallet.WalletTransaction;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.wallet.NodeEntry;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.Label;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;

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
                if(denominationUnit.getValue() == BitcoinUnit.SATOSHIS) {
                    Long.parseLong(denomination);
                }
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
            try {
                Map<Wallet, Storage> openWallets = AppServices.get().getOpenWallets();
                if (openWallets.isEmpty()) {
                    throw new Exception("No wallet found. Please open a wallet in Sparrow first.");
                }

                Map.Entry<Wallet, Storage> firstWallet = openWallets.entrySet().iterator().next();
                Wallet wallet = firstWallet.getKey();
                Storage storage = firstWallet.getValue();
                bitcoinAddress = NostrPublisher.getNewReceiveAddress(storage, wallet);

                double recipientDustThreshold = (double) PaymentController.getRecipientDustThreshold(bitcoinAddress) / 100000000;
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

                Alert alert = new Alert(AlertType.INFORMATION);
                alert.setHeaderText(null);
                assert event != null;

                JoinstrEvent joinstrEvent = new JoinstrEvent(event.getContent());

                ArrayList<JoinstrPool> pools = Config.get().getPoolStore();
                JoinstrPool pool = new JoinstrPool(joinstrEvent.relay, joinstrEvent.public_key, joinstrEvent.denomination, joinstrEvent.peers, joinstrEvent.timeout);
                pools.add(pool);
                Config.get().setPoolStore(pools);

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
                            "\nPeers: " + peers
            );

            Map<BlockTransactionHashIndex, WalletNode> utxos = wallet.getWalletUtxos();

            for (Map.Entry<BlockTransactionHashIndex, WalletNode> entry : utxos.entrySet()) {
                BlockTransactionHashIndex utxo = entry.getKey();
                WalletNode node = entry.getValue();
            }

            UtxoCircleDialog dialog = new UtxoCircleDialog(wallet);
            dialog.showAndWait();

        } catch (Exception e) {
            showError("An error occurred: " + e.getMessage());
        }
    }

    private PSBT createPSBT(String paymentLabel, long amount, long denomination) throws InsufficientFundsException {

        //  PSBT from WalletTransaction
        double feeRate = 1.0;
        double longTermFeeRate = 10.0;
        long fee = 10L;
        long dustThreshold = getRecipientDustThreshold(coinjoinAddress);
        long satsLeft = amount;

        ArrayList<Payment> payments = new ArrayList<Payment>();
        while(satsLeft == denomination || satsLeft > denomination + dustThreshold) {
            Payment payment = new Payment(coinjoinAddress, paymentLabel, denomination, false);
            satsLeft -= denomination;
            payment.setType(Payment.Type.COINJOIN);
            payments.add(payment);
        }

        long noInputsFee = getWalletForm().getWallet().getNoInputsFee(payments, feeRate);
        long costOfChange = getWalletForm().getWallet().getCostOfChange(feeRate, longTermFeeRate);
        List<UtxoSelector> selectors = new ArrayList<>(List.of(new BnBUtxoSelector(noInputsFee, costOfChange), new KnapsackUtxoSelector(noInputsFee)));

        SpentTxoFilter spentTxoFilter = new SpentTxoFilter(null);
        List<TxoFilter> txoFilters = List.of(spentTxoFilter, new FrozenTxoFilter(), new CoinbaseTxoFilter(getWalletForm().getWallet()));

        ArrayList<byte[]> opReturns = new ArrayList<>();
        TreeSet<WalletNode> excludedChangeNodes = new TreeSet<>();

        Integer currentBlockHeight = AppServices.getCurrentBlockHeight();
        boolean groupByAddress = false;
        boolean includeMempoolOutputs = false;

        WalletTransaction walletTransaction = getWalletForm().getWallet().createWalletTransaction(selectors, txoFilters, payments, opReturns, excludedChangeNodes, feeRate, longTermFeeRate, fee, currentBlockHeight, groupByAddress, includeMempoolOutputs);
        return walletTransaction.createPSBT();
    }

    private long getRecipientDustThreshold(Address address) {
        TransactionOutput txOutput = new TransactionOutput(new Transaction(), 1L, address.getOutputScript());
        return address.getScriptType().getDustThreshold(txOutput, Transaction.DUST_RELAY_TX_FEE);
    }

    public static void shareCredentials(Identity poolIdentity, String relayUrl){
        Map<String, String> poolCredentials = new HashMap<>();
        poolCredentials.put("id", "pool_id_here");
        poolCredentials.put("public_key", "pool_pubkey_here");
        poolCredentials.put("denomination", "0.1");
        poolCredentials.put("peers", "5");
        poolCredentials.put("timeout", String.valueOf(System.currentTimeMillis() / 1000 + 3600));
        poolCredentials.put("relay", "wss://nos.lol");
        poolCredentials.put("private_key", "pool_privkey_here");
        poolCredentials.put("fee_rate", "1");

        NostrListener listener = new NostrListener(poolIdentity, relayUrl, poolCredentials);

        listener.startListening(decryptedMessage -> {
            logger.info("Received message: " + decryptedMessage);
        });
    }
    private void showError(String message) {
        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private Address getNewReceiveAddress() {
        NodeEntry freshNodeEntry = getWalletForm().getFreshNodeEntry(KeyPurpose.RECEIVE, null);
        return freshNodeEntry.getAddress();
    }

}