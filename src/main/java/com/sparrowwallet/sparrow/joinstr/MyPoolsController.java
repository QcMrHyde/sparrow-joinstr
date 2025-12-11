package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.sparrow.io.Storage;

import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.joinstr.control.JoinstrInfoPane;
import com.sparrowwallet.sparrow.joinstr.control.JoinstrPoolList;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class MyPoolsController extends JoinstrFormController {

    @FXML
    private VBox contentVBox;

    @FXML
    private TextField searchTextField;

    private static final JoinstrPoolList joinstrPoolList = new JoinstrPoolList();
    private JoinstrInfoPane joinstrInfoPane;

    @Override
    public void initializeView() {
        try {

            // Add pool store data
            addPoolStoreData();

            joinstrInfoPane = new JoinstrInfoPane();
            joinstrInfoPane.initInfoPane();
            joinstrInfoPane.setVisible(false);
            joinstrInfoPane.setManaged(false);

            joinstrPoolList.setOnPoolSelectedListener(pool -> {
                if (pool != null) {
                    getJoinstrController().setSelectedPool(pool);
                    joinstrInfoPane.setVisible(true);
                    joinstrInfoPane.setManaged(true);
                    joinstrInfoPane.updatePoolInfo(pool);
                } else {
                    joinstrInfoPane.setVisible(false);
                    joinstrInfoPane.setManaged(false);
                }
            });

            JoinstrPool selectedPool = getJoinstrController().getSelectedPool();
            if(selectedPool != null) {
                joinstrPoolList.setSelectedPool(selectedPool);
            }

            ToggleButton importButton = new ToggleButton("Import pools");
            importButton.setOnAction(event -> {
                JoinstrPool.importPoolsFile(Storage.getSparrowDir().getPath());
                addPoolStoreData();
            });

            ToggleButton exportButton = new ToggleButton("Export pools");
            exportButton.setOnAction(event -> {

                try {
                    JoinstrPool.exportPoolsFile(Storage.getSparrowDir().getPath());
                } catch (IOException e) {
                    showError("Error writing file to disk.");
                }

            });

            HBox buttonsHBox = new HBox();
            buttonsHBox.setSpacing(15);
            buttonsHBox.getChildren().addAll(importButton, exportButton);

            contentVBox.setSpacing(10);
            contentVBox.getChildren().addAll(joinstrPoolList, joinstrInfoPane, buttonsHBox);

            searchTextField.textProperty().addListener((observable, oldValue, newValue) -> {
                filterPools(newValue);
            });

        } catch (Exception e) {
            if(e != null) {
                e.printStackTrace();
            }
        }

    }

    private void addPoolStoreData() {
        ArrayList<JoinstrPool> pools = Config.get().getPoolStore();
        boolean poolStoreChanged = false;

        clearPoolList();
        for (int idx=0; idx<pools.size(); idx++) {
            long timeout = Long.parseLong(pools.get(idx).getTimeout());
            if (timeout >= Instant.now().getEpochSecond()) {
                joinstrPoolList.addPool(pools.get(idx));
            } else {
                pools.get(idx).stopListeningForCredentials();
                pools.remove(pools.get(idx));
                idx--;
                poolStoreChanged = true;
            }
        }

        if(poolStoreChanged)
            Config.get().setPoolStore(pools);

    }

    private void filterPools(String searchText) {
        joinstrPoolList.filterPools(searchText);
    }

    public void handleSearchButton(ActionEvent e) {
        if(e.getSource()==searchTextField) {
            System.out.println(searchTextField.getText());
        };
    }

    public static void clearPoolList() {
        joinstrPoolList.clearPools();
    }

}
