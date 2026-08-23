package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.sparrow.io.Config;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;

public class SettingsController extends JoinstrFormController {

    @FXML
    TextField nostrRelayTextField;

    @FXML
    TextField autctApiUrlTextField;

    @Override
    public void initializeView() {

        try {

            nostrRelayTextField.setText(Config.get().getNostrRelay());
            nostrRelayTextField.textProperty().addListener(new ChangeListener<String>() {
                @Override
                public void changed(ObservableValue<? extends String> observable,
                                    String oldValue, String newValue) {
                    setDefaultNostrRelayIfEmpty();
                    Config.get().setNostrRelay(nostrRelayTextField.getText());
                }
            });
            setDefaultNostrRelayIfEmpty();

            autctApiUrlTextField.setPromptText(AutctClient.DEFAULT_API_URL);
            autctApiUrlTextField.setText(Config.get().getAutctApiUrl());
            autctApiUrlTextField.textProperty().addListener((observable, oldValue, newValue) ->
                    Config.get().setAutctApiUrl(newValue == null ? null : newValue.trim()));

        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void refreshView() {

    }

    public void setDefaultNostrRelayIfEmpty() {
        if(nostrRelayTextField.getText() == null || nostrRelayTextField.getText().isEmpty()) {
            nostrRelayTextField.setText("wss://nos.lol");
        }
    }

    @Override
    public void close() throws Exception {

    }
}
