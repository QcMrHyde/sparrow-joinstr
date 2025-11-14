package com.sparrowwallet.sparrow.joinstr.control;

import com.sparrowwallet.sparrow.joinstr.JoinstrPool;
import com.sparrowwallet.sparrow.control.QRDisplayDialog;

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

import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;

import java.util.Map;
import java.util.function.Consumer;

import com.sparrowwallet.sparrow.joinstr.JoinPoolHandler;
import javafx.application.Platform;
import nostr.base.PublicKey;

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
        poolTableView.getStyleClass().add("joinstr-list-tableview");

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
        pubkeyColumn.setPrefWidth(150);

        TableColumn<JoinstrPool, String> denominationColumn = new TableColumn<>("Denomination");
        denominationColumn.setCellValueFactory(new PropertyValueFactory<>("denomination"));
        denominationColumn.setPrefWidth(100);

        TableColumn<JoinstrPool, String> peersColumn = new TableColumn<>("Peers");
        peersColumn.setCellValueFactory(new PropertyValueFactory<>("peers"));
        peersColumn.setPrefWidth(50);

        TableColumn<JoinstrPool, String> timeoutColumn = new TableColumn<>("Timeout");
        timeoutColumn.setCellValueFactory(new PropertyValueFactory<>("timeout"));
        timeoutColumn.setPrefWidth(100);

        TableColumn<JoinstrPool, String> statusColumn = new TableColumn<>("Status");
        statusColumn.setCellValueFactory(param -> param.getValue().statusProperty());
        statusColumn.setPrefWidth(150);

        poolTableView.getColumns().addAll(
                relayColumn,
                pubkeyColumn,
                denominationColumn,
                peersColumn,
                timeoutColumn,
                statusColumn
        );

        poolTableView.setOnSort(event -> {

            ObservableList<TableColumn<JoinstrPool, ?>> sortOrder = poolTableView.getSortOrder();

            if (!sortOrder.isEmpty()) {
                // Create a custom comparator based on sort order
                Comparator<JoinstrPool> comparator = null;

                for (TableColumn<JoinstrPool, ?> column : sortOrder) {
                    Comparator<JoinstrPool> columnComparator = null;

                    if (column == relayColumn) {
                        columnComparator = Comparator.comparing(JoinstrPool::getRelay, String.CASE_INSENSITIVE_ORDER);
                    } else if (column == pubkeyColumn) {
                        columnComparator = Comparator.comparing(JoinstrPool::getPubkey, String.CASE_INSENSITIVE_ORDER);
                    } else if (column == denominationColumn) {
                        columnComparator = Comparator.comparing(JoinstrPool::getDenomination, String.CASE_INSENSITIVE_ORDER);
                    } else if (column == peersColumn) {
                        columnComparator = Comparator.comparing(JoinstrPool::getPeers, String.CASE_INSENSITIVE_ORDER);
                    } else if (column == timeoutColumn) {
                        columnComparator = Comparator.comparing(JoinstrPool::getTimeout, String.CASE_INSENSITIVE_ORDER);
                    }

                    // Handle sort type (ascending/descending)
                    if (columnComparator != null) {
                        if (column.getSortType() == TableColumn.SortType.DESCENDING) {
                            columnComparator = columnComparator.reversed();
                        }

                        // Chain comparators for multi-column sorting
                        if (comparator == null) {
                            comparator = columnComparator;
                        } else {
                            comparator = comparator.thenComparing(columnComparator);
                        }
                    }
                }

                if (comparator != null) {
                    FXCollections.sort(poolData, comparator);
                }
            }

            event.consume();

        });

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
                            tags.add(new PubKeyTag(new PublicKey(pool.getPubkey()))); // Send to pool creator's pubkey

                            NIP04 nip04 = new NIP04(identity, new PublicKey(pool.getPubkey())); // Use pool's pubkey
                            String encryptedContent = nip04.encrypt(identity, requestContent, new PublicKey(pool.getPubkey()));

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

                            pool.setStatus("waiting for credentials");
                            joinButton.setDisable(true);

                            JoinPoolHandler handler = new JoinPoolHandler(identity, pool, status -> {
                                Platform.runLater(() -> {
                                    pool.setStatus(status);
                                });
                            });
                            handler.startListeningForCredentials();
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