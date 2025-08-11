package com.sparrowwallet.sparrow.joinstr;

import com.google.gson.Gson;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.joinstr.control.JoinstrPoolStoreWrapper;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import javafx.beans.property.SimpleStringProperty;
import javafx.stage.FileChooser;
import nostr.id.Identity;

public class JoinstrPool {

    private final SimpleStringProperty relay;
    private final SimpleStringProperty pubkey;
    private final SimpleStringProperty denomination;
    private final SimpleStringProperty peers;
    private final SimpleStringProperty timeout;
    private final String privateKey;

    public JoinstrPool(String relay, String pubkey, String denomination,
                       String peers, String timeout) {
        this.relay = new SimpleStringProperty(relay);
        this.pubkey = new SimpleStringProperty(pubkey);
        this.denomination = new SimpleStringProperty(denomination);
        this.peers = new SimpleStringProperty(peers);
        this.timeout = new SimpleStringProperty(timeout);
        this.privateKey = "";
    }

    public JoinstrPool(String relay, String pubkey, String denomination,
                       String peers, String timeout, String privateKey) {
        this.relay = new SimpleStringProperty(relay);
        this.pubkey = new SimpleStringProperty(pubkey);
        this.denomination = new SimpleStringProperty(denomination);
        this.peers = new SimpleStringProperty(peers);
        this.timeout = new SimpleStringProperty(timeout);
        this.privateKey = privateKey;
    }

    public String getRelay() { return relay.get(); }
    public String getPubkey() { return pubkey.get(); }
    public String getDenomination() { return denomination.get(); }
    public String getPeers() { return peers.get(); }
    public String getTimeout() { return timeout.get(); }
    public Identity getJoinstrIdentity() { return Identity.create(privateKey); }

    public static void importPoolsFile(String directoryPath) {

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open File");
        fileChooser.setInitialFileName("pools.json");
        fileChooser.setInitialDirectory(new File(directoryPath));
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Json files", "*.json")
        );
        File file = fileChooser.showOpenDialog(null);
        Scanner scanner = null;
        try {
            scanner = new Scanner(file);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        StringBuilder text = new StringBuilder();
        while (scanner.hasNextLine()) {
            text.append(scanner.nextLine()).append("\n");
        }
        scanner.close();

        try {
            Gson gson = new Gson();
            JoinstrPoolStoreWrapper psWrapper = gson.fromJson(text.toString(), JoinstrPoolStoreWrapper.class);
            Config.get().setPoolStore(psWrapper.poolsList);
        } catch (Exception e) {
            if(e == null) {}
        }
    }

    public static void exportPoolsFile(String directoryPath) throws IOException {

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save File");
        fileChooser.setInitialFileName("pools.json");
        fileChooser.setInitialDirectory(new File(directoryPath));
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Json files", "*.json")
        );

        File file = fileChooser.showSaveDialog(null);
        savePoolsFile(file.getPath());

    }

    public static void savePoolsFile(String filePath) throws IOException {

        Gson gson = new Gson();
        String poolsJson = gson.toJson(new JoinstrPoolStoreWrapper(Config.get().getPoolStore()));
        FileWriter writer = new FileWriter(new File(filePath));
        writer.write(poolsJson);
        writer.close();

    }

}
