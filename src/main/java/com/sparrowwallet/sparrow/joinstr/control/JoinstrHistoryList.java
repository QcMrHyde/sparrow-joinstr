package com.sparrowwallet.sparrow.joinstr.control;

import com.sparrowwallet.sparrow.joinstr.JoinstrHistoryEntry;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;

import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

public class JoinstrHistoryList extends VBox {

    private TableView<JoinstrHistoryEntry> historyTableView;
    private ObservableList<JoinstrHistoryEntry> historyData;
    private FilteredList<JoinstrHistoryEntry> filteredData;

    public JoinstrHistoryList() {
        initialize();
    }

    private void initialize() {
        historyTableView = new TableView<>();
        historyTableView.getStyleClass().add("joinstr-list-tableview");

        historyData = FXCollections.observableArrayList();
        filteredData = new FilteredList<>(historyData, p -> true);
        historyTableView.setItems(filteredData);

        TableColumn<JoinstrHistoryEntry, String> dateColumn = new TableColumn<>("Date");
        dateColumn.setCellValueFactory(cellData -> {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());
            String formattedDate = formatter.format(Instant.ofEpochSecond(cellData.getValue().getDate()));
            return new SimpleStringProperty(formattedDate);
        });
        dateColumn.setPrefWidth(150);

        TableColumn<JoinstrHistoryEntry, String> txidColumn = new TableColumn<>("Transaction ID");
        txidColumn.setCellValueFactory(new PropertyValueFactory<>("txid"));
        txidColumn.setPrefWidth(300);

        TableColumn<JoinstrHistoryEntry, String> relayColumn = new TableColumn<>("Relay");
        relayColumn.setCellValueFactory(new PropertyValueFactory<>("relay"));
        relayColumn.setPrefWidth(200);

        TableColumn<JoinstrHistoryEntry, String> amountColumn = new TableColumn<>("Amount");
        amountColumn.setCellValueFactory(cellData -> {
            double btc = cellData.getValue().getAmountSats() / 100000000.0;
            DecimalFormat df = new DecimalFormat("0.00000000");
            return new SimpleStringProperty(df.format(btc) + " BTC");
        });
        amountColumn.setPrefWidth(100);

        historyTableView.getColumns().addAll(dateColumn, txidColumn, relayColumn, amountColumn);

        historyTableView.setOnSort(event -> {
            ObservableList<TableColumn<JoinstrHistoryEntry, ?>> sortOrder = historyTableView.getSortOrder();
            if (!sortOrder.isEmpty()) {
                Comparator<JoinstrHistoryEntry> comparator = null;
                for (TableColumn<JoinstrHistoryEntry, ?> column : sortOrder) {
                    Comparator<JoinstrHistoryEntry> columnComparator = null;
                    if (column == dateColumn) {
                        columnComparator = Comparator.comparingLong(JoinstrHistoryEntry::getDate);
                    } else if (column == txidColumn) {
                        columnComparator = Comparator.comparing(JoinstrHistoryEntry::getTxid, String.CASE_INSENSITIVE_ORDER);
                    } else if (column == relayColumn) {
                        columnComparator = Comparator.comparing(JoinstrHistoryEntry::getRelay, String.CASE_INSENSITIVE_ORDER);
                    } else if (column == amountColumn) {
                        columnComparator = Comparator.comparingLong(JoinstrHistoryEntry::getAmountSats);
                    }

                    if (columnComparator != null) {
                        if (column.getSortType() == TableColumn.SortType.DESCENDING) {
                            columnComparator = columnComparator.reversed();
                        }
                        comparator = (comparator == null) ? columnComparator : comparator.thenComparing(columnComparator);
                    }
                }
                if (comparator != null) {
                    FXCollections.sort(historyData, comparator);
                }
            }
            event.consume();
        });

        getChildren().add(historyTableView);
        setVgrow(historyTableView, javafx.scene.layout.Priority.ALWAYS);
    }

    public void setHistoryEntries(java.util.List<JoinstrHistoryEntry> entries) {
        historyData.setAll(entries);
        // Default sort by date descending
        historyData.sort(Comparator.comparingLong(JoinstrHistoryEntry::getDate).reversed());
    }

    public void filterHistory(String searchText) {
        if (searchText == null || searchText.isEmpty()) {
            filteredData.setPredicate(p -> true);
        } else {
            String lowercaseFilter = searchText.toLowerCase();
            filteredData.setPredicate(entry -> 
                entry.getTxid().toLowerCase().contains(lowercaseFilter) ||
                entry.getRelay().toLowerCase().contains(lowercaseFilter)
            );
        }
    }
}
