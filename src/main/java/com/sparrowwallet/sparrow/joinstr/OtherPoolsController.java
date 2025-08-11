package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.sparrow.joinstr.control.JoinstrInfoPane;
import com.sparrowwallet.sparrow.joinstr.control.JoinstrPoolList;

import javafx.beans.property.SimpleStringProperty;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import nostr.event.impl.GenericEvent;

public class OtherPoolsController extends JoinstrFormController {

    @FXML
    private VBox contentVBox;

    @FXML
    private TextField searchTextField;

    private JoinstrPoolList joinstrPoolList;
    private JoinstrInfoPane joinstrInfoPane;

    @Override
    public void initializeView() {
        try {
            joinstrPoolList = new JoinstrPoolList();

            joinstrPoolList.configureWithJoinButtons();

            // Add sample pool data
            addSamplePoolData();

            joinstrInfoPane = new JoinstrInfoPane();
            joinstrInfoPane.initInfoPane();
            joinstrInfoPane.setVisible(false);
            joinstrInfoPane.setManaged(false);

            joinstrPoolList.setOnPoolSelectedListener(pool -> {
                if (pool != null) {
                    joinstrInfoPane.setVisible(true);
                    joinstrInfoPane.setManaged(true);
                    joinstrInfoPane.updatePoolInfo(pool);
                } else {
                    joinstrInfoPane.setVisible(false);
                    joinstrInfoPane.setManaged(false);
                }
            });

            contentVBox.getChildren().addAll(joinstrPoolList, joinstrInfoPane);

            searchTextField.textProperty().addListener((observable, oldValue, newValue) -> {
                filterPools(newValue);
            });

        } catch (Exception e) {
            if(e != null) {
                e.printStackTrace();
            }
        }
    }

    private void addSamplePoolData() {

        // Create the two sample pools
        JoinstrPool pool1 = new JoinstrPool(
                "wss://relay.joinstr.xyz",
                "03ab4...e92f",
                "0.001 BTC",
                "4/5",
                "00:00:00"
        );

        JoinstrPool pool2 = new JoinstrPool(
                "wss://nos.lol",
                "1bd340cb690bf3fd35106b2434230cf23d539d89824efbde1605b61c21fa9543",
                "0.005 BTC",
                "3/7",
                "00:00:00"
        );

        joinstrPoolList.addPool(pool1);
        joinstrPoolList.addPool(pool2);
    }

    private void filterPools(String searchText) {
        joinstrPoolList.filterPools(searchText);
    }

    public void handleSearchButton(ActionEvent e) {
        if(e.getSource() == searchTextField) {
            filterPools(searchTextField.getText());
        }
    }

}