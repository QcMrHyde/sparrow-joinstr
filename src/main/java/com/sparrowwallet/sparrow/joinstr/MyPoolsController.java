package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.sparrow.io.Storage;

import java.io.IOException;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class MyPoolsController extends JoinstrPoolListFormController {

    @FXML
    private VBox contentVBox;

    @FXML
    private TextField searchTextField;

    @Override
    public void initializeView() {
        try {
            super.initializeView();
            joinstrPoolList.filterOutdatedPools(false);

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

        } catch (Exception e) {
            if(e != null) {
                e.printStackTrace();
            }
        }

    }

    public void handleSearchButton(ActionEvent e) {
        if(e.getSource()==searchTextField) {
            System.out.println(searchTextField.getText());
        };
    }

}
