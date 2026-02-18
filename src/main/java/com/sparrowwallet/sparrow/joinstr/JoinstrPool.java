package com.sparrowwallet.sparrow.joinstr;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.sparrowwallet.sparrow.io.Config;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.logging.Logger;

import javafx.beans.property.SimpleStringProperty;
import javafx.stage.FileChooser;
import nostr.id.Identity;

public class JoinstrPool {

    private static final Logger logger = Logger.getLogger(JoinstrPool.class.getName());
    private final SimpleStringProperty relay;
    private final SimpleStringProperty pubkey;
    private final SimpleStringProperty denomination;
    private final SimpleStringProperty peers;
    private final SimpleStringProperty timeout;
    private final SimpleStringProperty status;
    private String privateKey;
    private JoinPoolHandler handler;

    public JoinstrPool(String relay, String pubkey, String denomination,
            String peers, String timeout) {
        this.relay = new SimpleStringProperty(relay);
        this.pubkey = new SimpleStringProperty(pubkey);
        this.denomination = new SimpleStringProperty(denomination);
        this.peers = new SimpleStringProperty(peers);
        this.timeout = new SimpleStringProperty(timeout);
        this.privateKey = "";
        this.status = new SimpleStringProperty("");
    }

    public JoinstrPool(String relay, String pubkey, String denomination,
            String peers, String timeout, String privateKey) {
        this.relay = new SimpleStringProperty(relay);
        this.pubkey = new SimpleStringProperty(pubkey);
        this.denomination = new SimpleStringProperty(denomination);
        this.peers = new SimpleStringProperty(peers);
        this.timeout = new SimpleStringProperty(timeout);
        this.privateKey = privateKey;
        this.status = new SimpleStringProperty("");

    }

    public JoinstrPool(String relay, String pubkey, String denomination,
            String peers, String timeout, String privateKey, String status) {
        this.relay = new SimpleStringProperty(relay);
        this.pubkey = new SimpleStringProperty(pubkey);
        this.denomination = new SimpleStringProperty(denomination);
        this.peers = new SimpleStringProperty(peers);
        this.timeout = new SimpleStringProperty(timeout);
        this.privateKey = privateKey;
        this.status = new SimpleStringProperty(status);

    }

    public String getRelay() {
        return relay.get();
    }

    public String getPubkey() {
        return pubkey.get();
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public String getDenomination() {
        return denomination.get();
    }

    public String getPeers() {
        return peers.get();
    }

    public String getPeersStatus() {
        String connectedPeers = "0";
        if (this.handler != null) {
            connectedPeers = String.valueOf(handler.getConnectedPeers());
        }
        return connectedPeers + "/" + peers.get();
    }

    public String getTimeout() {
        return timeout.get();
    }

    public Identity getJoinstrIdentity() {
        Identity joinstrIdentity = null;
        try {
            if (!privateKey.isEmpty())
                joinstrIdentity = Identity.create(privateKey);
            else if (handler != null && !handler.getPoolPrivateKey().isEmpty()) {
                privateKey = handler.getPoolPrivateKey();
                joinstrIdentity = Identity.create(privateKey);
            }

        } catch (Exception e) {
            logger.severe("Error: " + e.getMessage());
        }
        return joinstrIdentity;
    }

    public int getParsedPeers() {
        if (peers == null || peers.get().trim().isEmpty()) {
            return 0;
        }
        try {
            if (peers.get().contains("/")) {
                String[] parts = peers.get().split("/");
                return Integer.parseInt(parts[1].trim());
            } else {
                return Integer.parseInt(peers.get().trim());
            }
        } catch (Exception e) {
            return 0;
        }
    }

    public String getStatus() {
        return status.get();
    }

    public void setStatus(String status) {
        this.status.set(status);
    }

    public SimpleStringProperty statusProperty() {
        return status;
    }

    public static void importPoolsFile(String directoryPath) {

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open File");
        fileChooser.setInitialFileName("pools.json");
        fileChooser.setInitialDirectory(new File(directoryPath));
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Json files", "*.json"));
        File file = fileChooser.showOpenDialog(null);
        StringBuilder text = new StringBuilder();
        Scanner scanner;
        try {
            scanner = new Scanner(file);
            while (scanner.hasNextLine()) {
                text.append(scanner.nextLine()).append("\n");
            }
            scanner.close();

        } catch (FileNotFoundException | NullPointerException e) {
            logger.warning("Error: " + e.getMessage());
        }

        if (!text.isEmpty()) {
            try {
                Gson gson = new Gson();
                Type mapType = new TypeToken<JoinstrPoolStoreWrapper>() {
                }.getType();
                JoinstrPoolStoreWrapper psWrapper = gson.fromJson(text.toString(), mapType);
                Config.get().setPoolStore(psWrapper.getPools());
            } catch (Exception e) {
                logger.warning("Error: " + e.getMessage());
            }
        }

    }

    public static void exportPoolsFile(String directoryPath) throws IOException {

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save File");
        fileChooser.setInitialFileName("pools.json");
        fileChooser.setInitialDirectory(new File(directoryPath));
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Json files", "*.json"));

        File file = fileChooser.showSaveDialog(null);
        if (file != null)
            savePoolsFile(file.getPath());

    }

    public static void savePoolsFile(String filePath) throws IOException {

        Gson gson = new Gson();
        ArrayList<JoinstrPool> poolStore = Config.get().getPoolStore();
        JoinstrPool[] pools = poolStore.toArray(new JoinstrPool[0]);
        String poolsJson = gson.toJson(new JoinstrPoolStoreWrapper(pools));
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(poolsJson);
        }

    }

    public void startListeningForCredentials(Identity identity) {
        setStatus("waiting for credentials");

        try {
            this.handler = new JoinPoolHandler(identity, this, this::setStatus);
            handler.startListeningForCredentials();

        } catch (Exception e) {
            logger.severe("Error: " + e.getMessage());
        }
    }

    public void stopListeningForCredentials() {
        if (handler != null)
            handler.stop();
    }

    private JoinstrPoolData toJoinstrPoolData() {
        return new JoinstrPoolData(this);
    }

    private class JoinstrPoolData {

        private final String relay;
        private final String pubkey;
        private final String denomination;
        private final String peers;
        private final String timeout;
        private final String status;
        private final String privateKey;

        public JoinstrPoolData(JoinstrPool joinstrPool) {
            this.relay = joinstrPool.getRelay();
            this.pubkey = joinstrPool.getPubkey();
            this.denomination = joinstrPool.getDenomination();
            this.peers = joinstrPool.getPeers();
            this.timeout = joinstrPool.getTimeout();
            this.status = joinstrPool.getStatus();
            this.privateKey = joinstrPool.getPrivateKey();
        }

        public JoinstrPool getPoolObject() {
            return new JoinstrPool(relay, pubkey, denomination, peers, timeout, privateKey, status);
        }
    }

    private static class JoinstrPoolStoreWrapper {
        public ArrayList<JoinstrPoolData> poolsList;

        public JoinstrPoolStoreWrapper(JoinstrPool[] poolsObj) {
            poolsList = new ArrayList<>();
            for (JoinstrPool pool : poolsObj) {
                poolsList.add(pool.toJoinstrPoolData());
            }
        }

        public ArrayList<JoinstrPool> getPools() {
            ArrayList<JoinstrPool> pools = new ArrayList<>();

            for (JoinstrPoolData poolData : poolsList) {
                pools.add(poolData.getPoolObject());
            }

            return pools;
        }
    }

}
