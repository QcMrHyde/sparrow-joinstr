package com.sparrowwallet.sparrow.joinstr;

import static com.sparrowwallet.sparrow.AppServices.showSuccessDialog;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.joinstr.control.WalletSelectionDialog;
import com.sparrowwallet.sparrow.wallet.PaymentController;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

import javafx.fxml.FXML;
import javafx.scene.control.TextField;

import nostr.event.impl.GenericEvent;

public class NewPoolController extends JoinstrFormController {

    @FXML
    private TextField denominationField;

    @FXML
    private TextField peersField;

    @Override
    public void initializeView() {
    }

    @Override
    public void refreshView() {

    }

    @Override
    public void close() throws Exception {
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
                DecimalFormat df = new DecimalFormat("#.########", DecimalFormatSymbols.getInstance(Locale.US));
                denomination = df.format(denominationValue);
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

            GenericEvent event = null;

            try (NostrPublisher nostrPublisher = new NostrPublisher()) {

                Map<Wallet, Storage> openWallets = AppServices.get().getOpenWallets();
                Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage> selectedWallet;

                if (openWallets.isEmpty()) {
                    throw new Exception("No wallet found. Please open a wallet in Sparrow first.");
                } else if (openWallets.keySet().stream().filter(Wallet::isValid).count() > 1) {
                    WalletSelectionDialog walletSelectionDialog = new WalletSelectionDialog(openWallets);
                    walletSelectionDialog.showAndWait();
                    selectedWallet = walletSelectionDialog.getSelectedWallet();
                    if (selectedWallet == null) {
                        throw new IllegalStateException("No wallet selected");
                    }
                } else {
                    selectedWallet = openWallets.entrySet().iterator().next();
                }

                wallet = selectedWallet.getKey();
                Storage storage = selectedWallet.getValue();
                bitcoinAddress = nostrPublisher.getNewReceiveAddress(storage, wallet);

                double recipientDustThreshold = (double) PaymentController.getRecipientDustThreshold(bitcoinAddress)
                        / 100000000;
                if (Double.parseDouble(denomination) <= recipientDustThreshold) {
                    throw new Exception("Denomination must be greater than recipient dust threshold ("
                            + recipientDustThreshold + ")");
                }

                event = nostrPublisher.publishCustomEvent(denomination, peers, bitcoinAddress.toString());

                if (event == null) {
                    showError("Failed to publish pool event");
                    return;
                }

                String poolPrivateKey = nostrPublisher.getPoolPrivateKey();
                JoinstrEvent joinstrEvent = JoinstrEvent.fromJson(event.getContent());

                JoinstrPool pool = new JoinstrPool(joinstrEvent.relay, joinstrEvent.public_key,
                        joinstrEvent.denomination, joinstrEvent.peers, joinstrEvent.timeout, poolPrivateKey);
                updatePoolStore(pool);

                getJoinstrController().setSelectedPool(pool);
                getJoinstrController().setJoinstrDisplay(JoinstrDisplay.MY_POOLS);

                pool.startListeningForCredentials(pool.getJoinstrIdentity());

            } catch (Exception e) {
                showError("Error: " + e.getMessage());
            }

            denominationField.clear();
            peersField.clear();

            showSuccessDialog(
                    "New Pool",
                    "Pool created successfully!\nEvent ID: " + event.getId() +
                            "\nDenomination: " + denomination +
                            "\nPeers: " + peers +
                            "\n\nWaiting for peers to join...");

            /*
             * Map<BlockTransactionHashIndex, WalletNode> utxos = wallet.getWalletUtxos();
             * 
             * for (Map.Entry<BlockTransactionHashIndex, WalletNode> entry :
             * utxos.entrySet()) {
             * BlockTransactionHashIndex utxo = entry.getKey();
             * WalletNode node = entry.getValue();
             * }
             * 
             * UtxoCircleDialog dialog = new UtxoCircleDialog(wallet);
             * dialog.showAndWait();
             */

        } catch (Exception e) {
            showError("Error: " + e.getMessage());
        }
    }

    private void updatePoolStore(JoinstrPool pool) throws IOException {

        ArrayList<JoinstrPool> pools = Config.get().getPoolStore();

        pools.removeIf(p -> p.getPubkey() != null && p.getPubkey().equals(pool.getPubkey()));
        pools.add(pool);
        Config.get().setPoolStore(pools);

        JoinstrPool.savePoolsFile(Storage.getJoinstrPoolsFile().getPath());

    }
}