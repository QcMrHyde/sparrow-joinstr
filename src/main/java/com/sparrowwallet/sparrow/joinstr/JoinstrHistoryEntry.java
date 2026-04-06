package com.sparrowwallet.sparrow.joinstr;

import com.google.gson.Gson;
import com.sparrowwallet.sparrow.io.Config;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

public class JoinstrHistoryEntry {
    private final SimpleStringProperty txid;
    private final SimpleStringProperty relay;
    private final SimpleLongProperty amountSats;
    private final SimpleLongProperty date;

    public JoinstrHistoryEntry(String txid, String relay, long amountSats, long date) {
        this.txid = new SimpleStringProperty(txid);
        this.relay = new SimpleStringProperty(relay);
        this.amountSats = new SimpleLongProperty(amountSats);
        this.date = new SimpleLongProperty(date);
    }

    public String getTxid() {
        return txid.get();
    }

    public SimpleStringProperty txidProperty() {
        return txid;
    }

    public String getRelay() {
        return relay.get();
    }

    public SimpleStringProperty relayProperty() {
        return relay;
    }

    public long getAmountSats() {
        return amountSats.get();
    }

    public SimpleLongProperty amountSatsProperty() {
        return amountSats;
    }

    public long getDate() {
        return date.get();
    }

    public SimpleLongProperty dateProperty() {
        return date;
    }

    private JoinstrHistoryData toData() {
        return new JoinstrHistoryData(this);
    }

    public static class JoinstrHistoryData {
        private String txid;
        private String relay;
        private long amountSats;
        private long date;

        public JoinstrHistoryData(JoinstrHistoryEntry entry) {
            this.txid = entry.getTxid();
            this.relay = entry.getRelay();
            this.amountSats = entry.getAmountSats();
            this.date = entry.getDate();
        }

        public JoinstrHistoryEntry toEntry() {
            return new JoinstrHistoryEntry(txid, relay, amountSats, date);
        }
    }

    public static class JoinstrHistoryStoreWrapper {
        public ArrayList<JoinstrHistoryData> historyList;

        public JoinstrHistoryStoreWrapper(ArrayList<JoinstrHistoryEntry> entries) {
            historyList = new ArrayList<>();
            for (JoinstrHistoryEntry entry : entries) {
                historyList.add(entry.toData());
            }
        }

        public ArrayList<JoinstrHistoryEntry> getEntries() {
            ArrayList<JoinstrHistoryEntry> entries = new ArrayList<>();
            if (historyList != null) {
                for (JoinstrHistoryData data : historyList) {
                    entries.add(data.toEntry());
                }
            }
            return entries;
        }
    }

    public static void saveHistoryFile(String filePath) throws IOException {
        Gson gson = new Gson();
        ArrayList<JoinstrHistoryEntry> historyStore = Config.get().getHistoryStore();
        String historyJson = gson.toJson(new JoinstrHistoryStoreWrapper(historyStore));
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(historyJson);
        }
    }
}
