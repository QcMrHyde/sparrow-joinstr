package com.sparrowwallet.sparrow.joinstr;

import static com.sparrowwallet.sparrow.AppServices.showSuccessDialog;

import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.wallet.PaymentController;

import java.util.ArrayList;
import java.util.Map;

import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;

import nostr.event.impl.GenericEvent;

public class NewPoolController extends JoinstrFormController {
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
            try {
                Map<Wallet, Storage> openWallets = AppServices.get().getOpenWallets();
                if (openWallets.isEmpty()) {
                    throw new Exception("No wallet found. Please open a wallet in Sparrow first.");
                }

                Map.Entry<Wallet, Storage> firstWallet = openWallets.entrySet().iterator().next();
                Wallet wallet = firstWallet.getKey();
                Storage storage = firstWallet.getValue();
                bitcoinAddress = NostrPublisher.getNewReceiveAddress(storage, wallet);

                double recipientDustThreshold = (double)PaymentController.getRecipientDustThreshold(bitcoinAddress) / 100000000;
                if(Double.parseDouble(denomination) <= recipientDustThreshold) {
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

                // Custom class for ease of use
                JoinstrEvent joinstrEvent = new JoinstrEvent(event);

                // Add pool to pool store in Config
                ArrayList<JoinstrPool> pools = Config.get().getPoolStore();
                JoinstrPool pool = new JoinstrPool(joinstrEvent);
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

        } catch (Exception e) {
            showError("An error occurred: " + e.getMessage());
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}