package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.sparrow.io.Storage;

import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.joinstr.control.JoinstrInfoPane;
import com.sparrowwallet.sparrow.joinstr.control.JoinstrPoolList;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.logging.Logger;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class MyPoolsController extends JoinstrFormController {

    private static final Logger logger = Logger.getLogger(MyPoolsController.class.getName());

    @FXML
    private VBox contentVBox;

    @FXML
    private TextField searchTextField;

    private final JoinstrPoolList joinstrPoolList = new JoinstrPoolList();
    private JoinstrInfoPane joinstrInfoPane;
    private final ToggleButton exportButton = new ToggleButton("Export pools");

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
            showError(e.getMessage());
        }

    }

    @Override
    public void refreshView() {
        addPoolStoreData();
    }

    private void addPoolStoreData() {
        ArrayList<JoinstrPool> pools = Config.get().getPoolStore();
        boolean poolStoreChanged = false;

        clearPoolList();
        Iterator<JoinstrPool> iterator = pools.iterator();
        while (iterator.hasNext()) {
            JoinstrPool pool = iterator.next();
            long timeout = Long.parseLong(pool.getTimeout());
            if (timeout >= Instant.now().getEpochSecond()) {
                joinstrPoolList.addPool(pool);
            } else {
                pool.stopListeningForCredentials();
                iterator.remove();
                poolStoreChanged = true;
            }
        }

        if(poolStoreChanged)
            Config.get().setPoolStore(pools);

        exportButton.setDisable(joinstrPoolList.isEmpty());
    }

    private void filterPools(String searchText) {
        joinstrPoolList.filterPools(searchText);
    }

    public void handleSearchButton(ActionEvent e) {
        if(e.getSource()==searchTextField) {
            logger.info(searchTextField.getText());
        }
    }

    public void clearPoolList() {
        joinstrPoolList.clearPools();
    }

    @Override
    public void close() throws Exception {
        joinstrPoolList.clearPools();
        joinstrInfoPane = null;
    }
}
