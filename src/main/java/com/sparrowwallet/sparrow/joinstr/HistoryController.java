package com.sparrowwallet.sparrow.joinstr;

import java.util.logging.Logger;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;

public class HistoryController extends JoinstrFormController {

    private static final Logger logger = Logger.getLogger(HistoryController.class.getName());

    @FXML
    private TextField searchTextField;

    @Override
    public void initializeView() {

    }

    @Override
    public void refreshView() {

    }

    public void handleSearchButton(ActionEvent e) {
        if(e.getSource()==searchTextField) {
            logger.info(searchTextField.getText());
        }
    }

    @Override
    public void close() throws Exception {

    }
}
