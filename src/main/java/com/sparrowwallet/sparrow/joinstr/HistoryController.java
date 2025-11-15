package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.sparrow.io.Storage;

import java.io.IOException;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class HistoryController extends JoinstrPoolListFormController {

    @FXML
    private TextField searchTextField;

    @FXML
    private VBox contentVBox;

    @Override
    public void initializeView() {

        super.initializeView();
        joinstrPoolList.filterOutdatedPools(true);

        ToggleButton importButton = new ToggleButton("Import pools");
        importButton.setOnAction(event -> {
            JoinstrPool.importPoolsFile(Storage.getSparrowDir().getPath());
            addPoolsData();
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
            joinstrPoolList.filterPools(newValue);
        });

    }

    public void handleSearchButton(ActionEvent e) {
        if(e.getSource()==searchTextField) {
            System.out.println(searchTextField.getText());
        };
    }

}
