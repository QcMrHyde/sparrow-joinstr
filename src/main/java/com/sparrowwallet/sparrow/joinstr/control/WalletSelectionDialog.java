package com.sparrowwallet.sparrow.joinstr.control;

import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Storage;

import java.util.Map;
import java.util.Objects;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.layout.Pane;

public class WalletSelectionDialog extends Dialog<Void> {

    private final ListView<String> lvWallets;
    private final Map<com.sparrowwallet.drongo.wallet.Wallet, Storage> openedWallets;

    private Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage> selectedWallet = null;
    public Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage> getSelectedWallet() { return selectedWallet; }

    public WalletSelectionDialog(Map<Wallet, Storage> wallets) {

        openedWallets = wallets;
        lvWallets = new ListView<String>();

        for(Wallet wallet : wallets.keySet()) {
            if(wallet.isValid())
                lvWallets.getItems().add(wallet.getName());
        }
        lvWallets.setPrefHeight(lvWallets.getItems().size() * 25);

        final DialogPane dialogPane = getDialogPane();
        dialogPane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        AppServices.setStageIcon(dialogPane.getScene().getWindow());

        Pane canvas = new Pane();
        canvas.getChildren().add(lvWallets);

        dialogPane.setPadding(new Insets(10,10,10,10));
        dialogPane.setContent(canvas);

        ButtonType registerButtonType = new ButtonType("Select wallet", ButtonBar.ButtonData.OK_DONE);
        dialogPane.getButtonTypes().add(registerButtonType);

        setOnCloseRequest(e -> {
            for(Map.Entry<com.sparrowwallet.drongo.wallet.Wallet, Storage> walletEntry : openedWallets.entrySet()) {
                if(Objects.equals(walletEntry.getKey().getName(), lvWallets.getSelectionModel().getSelectedItem()))
                    selectedWallet = walletEntry;
            }
        });

        setTitle("Wallet Selection");
        AppServices.moveToActiveWindowScreen(this);

    }

}
