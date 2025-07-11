package com.sparrowwallet.sparrow.joinstr.control;

import com.sparrowwallet.sparrow.joinstr.JoinstrPool;
import com.sparrowwallet.sparrow.control.QRDisplayDialog;

import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import nostr.id.Identity;
import nostr.event.BaseTag;
import nostr.event.tag.PubKeyTag;
import nostr.api.NIP04;
import nostr.event.impl.GenericEvent;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.Button;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import java.util.List;
import java.util.ArrayList;

import java.util.Map;
import java.util.function.Consumer;

public class JoinstrPoolList extends VBox {

    private TableView<JoinstrPool> poolTableView;
    private ObservableList<JoinstrPool> poolData;
    private FilteredList<JoinstrPool> filteredData;
    private Consumer<JoinstrPool> onPoolSelectedListener;

    public JoinstrPoolList() {
        initialize();
    }

    private void initialize() {
        // Create the table
        poolTableView = new TableView<>();
        poolTableView.setStyle("-fx-background-color: #222222; -fx-text-fill: white;");

        // Create data storage
        poolData = FXCollections.observableArrayList();
        filteredData = new FilteredList<>(poolData, p -> true);
        poolTableView.setItems(filteredData);

        // Create standard columns
        TableColumn<JoinstrPool, String> relayColumn = new TableColumn<>("Relay");
        relayColumn.setCellValueFactory(new PropertyValueFactory<>("relay"));
        relayColumn.setPrefWidth(150);

        TableColumn<JoinstrPool, String> pubkeyColumn = new TableColumn<>("Pubkey");
        pubkeyColumn.setCellValueFactory(new PropertyValueFactory<>("pubkey"));
        pubkeyColumn.setPrefWidth(200);

        TableColumn<JoinstrPool, String> denominationColumn = new TableColumn<>("Denomination");
        denominationColumn.setCellValueFactory(new PropertyValueFactory<>("denomination"));
        denominationColumn.setPrefWidth(150);

        TableColumn<JoinstrPool, String> peersColumn = new TableColumn<>("Peers");
        peersColumn.setCellValueFactory(new PropertyValueFactory<>("peers"));
        peersColumn.setPrefWidth(80);

        TableColumn<JoinstrPool, String> timeoutColumn = new TableColumn<>("Timeout");
        timeoutColumn.setCellValueFactory(new PropertyValueFactory<>("timeout"));
        timeoutColumn.setPrefWidth(100);

        poolTableView.getColumns().addAll(
                relayColumn,
                pubkeyColumn,
                denominationColumn,
                peersColumn,
                timeoutColumn
        );

        poolTableView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldSelection, newSelection) -> {
                    if (onPoolSelectedListener != null) {
                        onPoolSelectedListener.accept(newSelection);
                    }
                }
        );

        getChildren().add(poolTableView);
        setVgrow(poolTableView, javafx.scene.layout.Priority.ALWAYS);
    }

    public void configureWithJoinButtons() {
        TableColumn<JoinstrPool, Void> joinButtonColumn = new TableColumn<>("");
        joinButtonColumn.setPrefWidth(100);
        joinButtonColumn.setStyle("-fx-alignment:CENTER;");
        joinButtonColumn.setCellFactory(new Callback<>() {
            @Override
            public TableCell<JoinstrPool, Void> call(final TableColumn<JoinstrPool, Void> param) {
                return new TableCell<>() {
                    private final Button joinButton = new Button("Join");
                    {
                        joinButton.setStyle(
                                "-fx-background-color: #2196F3; " +
                                        "-fx-text-fill: white; " +
                                        "-fx-cursor: hand;"
                        );

                        joinButton.setOnAction(event -> {
                            JoinstrPool pool = getTableView().getItems().get(getIndex());
                            Identity identity = Identity.generateRandomIdentity();
                            String pubkey = identity.getPublicKey().toString();
                            QRDisplayDialog qrDialog = new QRDisplayDialog(pubkey);
                            qrDialog.showAndWait();

                            String requestContent = "{\"type\": \"join_pool\"}";
                            List<BaseTag> tags = new ArrayList<>();
                            tags.add(new PubKeyTag(identity.getPublicKey()));

                            NIP04 nip04 = new NIP04(identity, identity.getPublicKey());
                            String encryptedContent = nip04.encrypt(identity, requestContent, identity.getPublicKey());

                            GenericEvent encrypted_event = new GenericEvent(
                                    identity.getPublicKey(),
                                    4,
                                    tags,
                                    encryptedContent
                            );

                            nip04.setEvent(encrypted_event);
                            nip04.sign();
                            nip04.send(Map.of("nos", pool.getRelay()));

                            System.out.println("Join request sent. Event ID:: " + encrypted_event.getId());

                            Alert waitingDialog = new Alert(Alert.AlertType.NONE);
                            waitingDialog.initModality(Modality.APPLICATION_MODAL);
                            waitingDialog.setTitle("Waiting");
                            waitingDialog.setHeaderText(null);

                            ProgressIndicator spinner = new ProgressIndicator();
                            Label waitingLabel = new Label("  Waiting for pool credentials...");
                            HBox content = new HBox(spinner, waitingLabel);
                            content.setSpacing(10);
                            waitingDialog.getDialogPane().setContent(content);

                            waitingDialog.show();
                        });
                    }

                    @Override
                    public void updateItem(Void item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty) {
                            setGraphic(null);
                        } else {
                            setGraphic(joinButton);
                        }
                    }
                };
            }
        });

        poolTableView.getColumns().add(joinButtonColumn);
    }

    public void addPool(JoinstrPool pool) {
        poolData.add(pool);
    }

    public void setSelectedPool(JoinstrPool poolToSelect) {
        poolTableView.getSelectionModel().select(poolToSelect);
    }
    public void clearPools() {
        poolData.clear();
    }

    public void filterPools(String searchText) {
        if (searchText == null || searchText.isEmpty()) {
            filteredData.setPredicate(p -> true);
        } else {
            String lowercaseFilter = searchText.toLowerCase();
            filteredData.setPredicate(pool ->
                    pool.getRelay().toLowerCase().contains(lowercaseFilter) ||
                            pool.getPubkey().toLowerCase().contains(lowercaseFilter) ||
                            pool.getDenomination().toLowerCase().contains(lowercaseFilter)
            );
        }
    }

    public void setOnPoolSelectedListener(Consumer<JoinstrPool> listener) {
        this.onPoolSelectedListener = listener;
    }

    public JoinstrPool getSelectedPool() {
        return poolTableView.getSelectionModel().getSelectedItem();
    }
}