package com.sparrowwallet.sparrow.joinstr.control;

import com.sparrowwallet.sparrow.Theme;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.joinstr.JoinstrPool;

import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

public class JoinstrInfoPane extends VBox {

    private Label relayValueLabel;
    private Label pubkeyValueLabel;
    private Label denominationValueLabel;
    private Label feeRateValueLabel;
    private Label statusValueLabel;
    private Label unsupportedLabel;
    private Label unsupportedValueLabel;

    public JoinstrInfoPane() {
        if(Config.get().getTheme() == Theme.DARK) {
            getStylesheets().add(getClass().getResource("../../darktheme.css").toExternalForm());
        }
        getStyleClass().add("joinstr-infopane");
        setSpacing(10);
    }

    public void initInfoPane() {
        Label titleLabel = new Label("Selected Pool Details");
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

        Label relayLabel = new Label("Relay:");
        relayLabel.getStyleClass().add("text-grey");
        relayValueLabel = new Label();

        Label pubkeyLabel = new Label("Pubkey:");
        pubkeyLabel.getStyleClass().add("text-grey");
        pubkeyValueLabel = new Label();

        Label denominationLabel = new Label("Denomination:");
        denominationLabel.getStyleClass().add("text-grey");
        denominationValueLabel = new Label();

        Label feeRateLabel = new Label("Fee rate:");
        feeRateLabel.getStyleClass().add("text-grey");
        feeRateValueLabel = new Label();

        Label statusLabel = new Label("Status:");
        statusLabel.getStyleClass().add("text-grey");
        statusValueLabel = new Label();

        unsupportedLabel = new Label("Not supported:");
        unsupportedLabel.getStyleClass().add("text-grey");
        unsupportedValueLabel = new Label();
        unsupportedValueLabel.setWrapText(true);

        if(Config.get().getTheme() == Theme.DARK) {
            relayValueLabel.setStyle("-fx-text-fill: white;");
            pubkeyValueLabel.setStyle("-fx-text-fill: white;");
            denominationValueLabel.setStyle("-fx-text-fill: white;");
            feeRateValueLabel.setStyle("-fx-text-fill: white;");
            statusValueLabel.setStyle("-fx-text-fill: white;");
            unsupportedValueLabel.setStyle("-fx-text-fill: white;");
        }

        detailsGrid.add(relayLabel, 0, 0);
        detailsGrid.add(relayValueLabel, 1, 0);
        detailsGrid.add(pubkeyLabel, 0, 1);
        detailsGrid.add(pubkeyValueLabel, 1, 1);
        detailsGrid.add(denominationLabel, 0, 2);
        detailsGrid.add(denominationValueLabel, 1, 2);
        detailsGrid.add(feeRateLabel, 0, 3);
        detailsGrid.add(feeRateValueLabel, 1, 3);
        detailsGrid.add(statusLabel, 0, 4);
        detailsGrid.add(statusValueLabel, 1, 4);
        detailsGrid.add(unsupportedLabel, 0, 5);
        detailsGrid.add(unsupportedValueLabel, 1, 5);

        // the row only appears for a pool this client cannot complete
        unsupportedLabel.managedProperty().bind(unsupportedLabel.visibleProperty());
        unsupportedValueLabel.managedProperty().bind(unsupportedValueLabel.visibleProperty());
        showUnsupported(null);

        getChildren().add(detailsGrid);
    }

    public void updatePoolInfo(JoinstrPool pool) {
        if (pool != null) {
            relayValueLabel.setText(pool.getRelay());
            pubkeyValueLabel.setText(pool.getPubkey());
            denominationValueLabel.setText(pool.getDenomination());
            feeRateValueLabel.setText(com.sparrowwallet.sparrow.joinstr.CoinjoinMath.formatFeeRate(pool.getParsedFeeRate()) + " sat/vB");
            statusValueLabel.textProperty().bind(pool.statusProperty());
            showUnsupported(pool.getUnsupportedReason());
        } else {
            clearPoolInfo();
        }
    }

    /** Show why a pool cannot be joined, or hide the row when it can. */
    private void showUnsupported(String reason) {
        boolean show = reason != null && !reason.isEmpty();
        unsupportedValueLabel.setText(show ? reason : "");
        unsupportedLabel.setVisible(show);
        unsupportedValueLabel.setVisible(show);
    }

    public void clearPoolInfo() {
        relayValueLabel.setText("");
        pubkeyValueLabel.setText("");
        denominationValueLabel.setText("");
        feeRateValueLabel.setText("");
        statusValueLabel.textProperty().unbind();
        statusValueLabel.setText("");
        showUnsupported(null);
    }
}