package com.sparrowwallet.sparrow.joinstr;

import static com.sparrowwallet.sparrow.AppServices.showSuccessDialog;

import com.sparrowwallet.sparrow.io.Config;

import java.util.ArrayList;

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

public class NewPoolController extends JoinstrFormController {

    @FXML
    private Label addressLabel;

    @FXML
    private TextField labelField;

    @FXML
    private TextField amountField;

    @FXML
    private TextField denominationField;

    @FXML
    private TextField peersField;

    @FXML
    private ComboBox<BlockTransactionHashIndex> utxosComboBox;

    @FXML
    private ComboBox<BitcoinUnit> denominationUnit;

    private Address coinjoinAddress;

    @Override
    public void initializeView() {

        coinjoinAddress = getNewReceiveAddress();
        addressLabel.setText(coinjoinAddress.getAddress());

        ObservableList<BlockTransactionHashIndex> utxos = FXCollections.observableArrayList(getWalletForm().getWallet().getWalletTxos().keySet().stream().filter(ref -> !ref.isSpent()).collect(Collectors.toList()));
        if(!utxos.isEmpty()) {
            utxosComboBox.setItems(utxos);
        }
        utxosComboBox.setPromptText("Select an UTXO");
        denominationUnit.setValue(BitcoinUnit.SATOSHIS);

    }

    @FXML
    private void setUtxoAmount(ActionEvent event) {
        switch(denominationUnit.getValue()) {
            case BTC -> {
                amountField.setText((utxosComboBox.getValue().getValue()) + " BTC");
            }
            case SATOSHIS -> {
                amountField.setText((utxosComboBox.getValue().getValue()) + " sats");
            }
        }
    }

    @FXML
    private void handleDenominationUnitChange(ActionEvent event) {
        switch(denominationUnit.getValue()) {
            case BTC -> {
                if(!amountField.getText().isEmpty()) {
                    amountField.setText((Double.parseDouble(amountField.getText().split(" ")[0]) / 100000000) + " BTC");
                }
            }
            case SATOSHIS -> {
                double amount = Double.parseDouble(amountField.getText().split(" ")[0]) * 100000000;
                amountField.setText(String.valueOf(amount).split("\\.")[0] + " sats");
            }
        }
    }

    @FXML
    private void handleCreateButton(ActionEvent event) {
        try {

            String denomination = denominationField.getText().trim();
            String peers = peersField.getText().trim();

            if (denomination.isEmpty() || peers.isEmpty()) {
                showError("Please enter denomination and peers to create a pool.");
                return;
            }

            try {
                double denominationValue = Double.parseDouble(denomination);
                if(denominationUnit.getValue() == BitcoinUnit.SATOSHIS) {
                    Long.parseLong(denomination);
                }
                if (denominationValue <= 0) {
                    showError("Denomination must be greater than 0");
                    return;
                }
            } catch (NumberFormatException e) {
                showError("Invalid denomination format");
                return;
            }

            try {
                int peersValue = Integer.parseInt(peers);
                if (peersValue <= 0) {
                    showError("Number of peers must be greater than 0");
                    return;
                }
            } catch (NumberFormatException e) {
                showError("Invalid number of peers format");
                return;
            }

            GenericEvent event = null;
            try {
                event = NostrPublisher.publishCustomEvent(denomination, peers);

                Alert alert = new Alert(AlertType.INFORMATION);
                alert.setHeaderText(null);
                assert event != null;

                // Custom class for ease of use
                JoinstrEvent joinstrEvent = new JoinstrEvent(event.getContent());

                // Add pool to pool store in Config
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
            amountField.clear();
            labelField.clear();

            assert event != null;
            showSuccessDialog(
                    "New Pool",
                    "Pool created successfully!\nEvent ID: " + event.getId() +
                            "\nDenomination: " + denomination +
                            "\nPeers: " + peers
            );

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