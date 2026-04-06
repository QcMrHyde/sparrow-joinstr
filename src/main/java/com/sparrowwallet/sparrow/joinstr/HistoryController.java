package com.sparrowwallet.sparrow.joinstr;

import java.util.logging.Logger;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import java.util.ArrayList;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.joinstr.control.JoinstrHistoryList;

public class HistoryController extends JoinstrFormController {

    private static final Logger logger = Logger.getLogger(HistoryController.class.getName());

    @FXML
    private VBox contentVBox;

    @FXML
    private TextField searchTextField;

    private final JoinstrHistoryList historyList = new JoinstrHistoryList();

    @Override
    public void initializeView() {
        try {
            contentVBox.setSpacing(10);
            contentVBox.getChildren().add(historyList);

            loadHistoryData();

            searchTextField.textProperty().addListener((observable, oldValue, newValue) -> {
                historyList.filterHistory(newValue);
            });
        } catch (Exception e) {
            logger.severe("Error initializing history view: " + e.getMessage());
        }
    }

    @Override
    public void refreshView() {
        loadHistoryData();
    }

    private void loadHistoryData() {
        ArrayList<JoinstrHistoryEntry> history = Config.get().getHistoryStore();
        historyList.setHistoryEntries(history);
    }

    public void handleSearchButton(ActionEvent e) {
        if (e.getSource() == searchTextField) {
            historyList.filterHistory(searchTextField.getText());
        }
    }

    @Override
    public void close() throws Exception {

    }
}
