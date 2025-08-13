package com.sparrowwallet.sparrow.joinstr.control;

import com.sparrowwallet.sparrow.joinstr.JoinstrPool;

import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

public class JoinstrInfoPane extends VBox {

    private Label titleLabel;
    private Label relayLabel;
    private Label pubkeyLabel;
    private Label denominationLabel;
    private Label relayValueLabel;
    private Label pubkeyValueLabel;
    private Label denominationValueLabel;

    public JoinstrInfoPane() {
        getStylesheets().add(getClass().getResource("../../darktheme.css").toExternalForm());
        getStyleClass().add("joinstr-infopane");
        setSpacing(10);
    }

    public void initInfoPane() {
        titleLabel = new Label("Selected Pool Details");
        titleLabel.getStyleClass().add("sub-title");
        getChildren().add(titleLabel);

        GridPane detailsGrid = new GridPane();
        detailsGrid.setHgap(10);
        detailsGrid.setVgap(10);

        ColumnConstraints column1 = new ColumnConstraints();
        column1.setPrefWidth(100);
        ColumnConstraints column2 = new ColumnConstraints();
        column2.setPrefWidth(400);
        detailsGrid.getColumnConstraints().addAll(column1, column2);

        relayLabel = new Label("Relay:");
        relayLabel.getStyleClass().add("text-grey");
        relayValueLabel = new Label();

        pubkeyLabel = new Label("Pubkey:");
        relayLabel.getStyleClass().add("text-grey");
        pubkeyValueLabel = new Label();

        denominationLabel = new Label("Denomination:");
        relayLabel.getStyleClass().add("text-grey");
        denominationValueLabel = new Label();

        detailsGrid.add(relayLabel, 0, 0);
        detailsGrid.add(relayValueLabel, 1, 0);
        detailsGrid.add(pubkeyLabel, 0, 1);
        detailsGrid.add(pubkeyValueLabel, 1, 1);
        detailsGrid.add(denominationLabel, 0, 2);
        detailsGrid.add(denominationValueLabel, 1, 2);

        getChildren().add(detailsGrid);
    }

    public void updatePoolInfo(JoinstrPool pool) {
        if (pool != null) {
            relayValueLabel.setText(pool.getRelay());
            pubkeyValueLabel.setText(pool.getPubkey());
            denominationValueLabel.setText(pool.getDenomination());
        } else {
            clearPoolInfo();
        }
    }

    public void clearPoolInfo() {
        relayValueLabel.setText("");
        pubkeyValueLabel.setText("");
        denominationValueLabel.setText("");
    }
}