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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
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
            try {
                Map<Wallet, Storage> openWallets = AppServices.get().getOpenWallets();
                if (openWallets.isEmpty()) {
                    throw new Exception("No wallet found. Please open a wallet in Sparrow first.");
                }

                Map.Entry<Wallet, Storage> firstWallet = openWallets.entrySet().iterator().next();
                wallet = firstWallet.getKey();
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
                assert event != null;

                String poolPrivateKey = NostrPublisher.getPoolPrivateKey();
                JoinstrEvent joinstrEvent = new JoinstrEvent(event.getContent());

                JoinstrPool pool = new JoinstrPool(joinstrEvent.relay, joinstrEvent.public_key, joinstrEvent.denomination, joinstrEvent.peers, joinstrEvent.timeout, poolPrivateKey);
                updatePoolStore(pool);

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

            /*
            Map<BlockTransactionHashIndex, WalletNode> utxos = wallet.getWalletUtxos();

            for (Map.Entry<BlockTransactionHashIndex, WalletNode> entry : utxos.entrySet()) {
                BlockTransactionHashIndex utxo = entry.getKey();
                WalletNode node = entry.getValue();
            }

            UtxoCircleDialog dialog = new UtxoCircleDialog(wallet);
            dialog.showAndWait();
             */

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