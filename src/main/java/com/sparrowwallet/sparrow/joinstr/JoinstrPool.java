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
    private final javafx.beans.property.SimpleIntegerProperty connectedPeers;
    private String privateKey;
    private String poolId = "";
    private String unsupportedReason;
    private String feeRate = "1";
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
        this.connectedPeers = new javafx.beans.property.SimpleIntegerProperty(0);
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
        this.connectedPeers = new javafx.beans.property.SimpleIntegerProperty(0);
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
        this.connectedPeers = new javafx.beans.property.SimpleIntegerProperty(0);
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

    /** Why this pool cannot be joined from Sparrow, or null if it can. */
    public String getUnsupportedReason() {
        return unsupportedReason;
    }

    public void setUnsupportedReason(String unsupportedReason) {
        this.unsupportedReason = unsupportedReason;
    }

    public boolean isJoinable() {
        return unsupportedReason == null;
    }

    /** The pool id from the announcement, which peers echo back in the credentials. */
    public String getPoolId() {
        return poolId;
    }

    public void setPoolId(String poolId) {
        this.poolId = poolId == null ? "" : poolId;
    }

    /**
     * The credentials a joiner is sent. Every field the pool announced is repeated here with the
     * same type, because a joiner checks the credentials against the announcement it chose before
     * trusting the private key inside them.
     */
    public Map<String, Object> toCredentials() {
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("id", poolId);
        credentials.put("public_key", getPubkey());
        credentials.put("denomination", asNumber(getDenomination(), 0d));
        credentials.put("peers", getParsedPeers());
        credentials.put("timeout", asLong(getTimeout()));
        credentials.put("relay", getRelay());
        credentials.put("fee_rate", getParsedFeeRate());
        credentials.put("private_key", privateKey);
        return credentials;
    }

    private static double asNumber(String value, double fallback) {
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long asLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getFeeRate() {
        return feeRate;
    }

    public void setFeeRate(String feeRate) {
        this.feeRate = feeRate;
    }

    /**
      * The advertised fee rate in sat/vB. Pools publish it as a JSON number, which other joinstr
      * clients derive from their own estimator and so is usually fractional.
      */
    public double getParsedFeeRate() {
        try {
            if (feeRate == null || feeRate.trim().isEmpty()) {
                return 1;
            }
            double parsed = Double.parseDouble(feeRate.trim());
            if (!Double.isFinite(parsed) || parsed <= 0) {
                return 1;
            }
            return parsed;
        } catch (Exception e) {
            return 1;
        }
    }

    public String getDenomination() {
        return denomination.get();
    }

    public String getPeers() {
        return peers.get();
    }

    public int getConnectedPeers() {
        return connectedPeers.get();
    }

    public void setConnectedPeers(int count) {
        FxDispatch.run(() -> connectedPeers.set(count));
    }

    public javafx.beans.property.SimpleIntegerProperty connectedPeersProperty() {
        return connectedPeers;
    }

    public String getPeersStatus() {
        return getConnectedPeers() + "/" + peers.get();
    }

    public javafx.beans.binding.StringBinding peersStatusProperty() {
        return javafx.beans.binding.Bindings.createStringBinding(
                () -> getConnectedPeers() + "/" + getPeers(),
                connectedPeers, peers);
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
        if (peers == null || peers.get() == null || peers.get().trim().isEmpty()) {
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
        // This file holds the private key of every pool, so it must not be world readable.
        SecureFile.write(filePath, poolsJson);

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
        private final String poolId;
        private final String feeRate;

        public JoinstrPoolData(JoinstrPool joinstrPool) {
            this.relay = joinstrPool.getRelay();
            this.pubkey = joinstrPool.getPubkey();
            this.denomination = joinstrPool.getDenomination();
            this.peers = joinstrPool.getPeers();
            this.timeout = joinstrPool.getTimeout();
            this.status = joinstrPool.getStatus();
            this.privateKey = joinstrPool.getPrivateKey();
            this.poolId = joinstrPool.getPoolId();
            this.feeRate = joinstrPool.getFeeRate();
        }

        public JoinstrPool getPoolObject() {
            JoinstrPool pool = new JoinstrPool(relay, pubkey, denomination, peers, timeout, privateKey, status);
            if (feeRate != null) {
                pool.setFeeRate(feeRate);
            }
            pool.setPoolId(poolId);
            return pool;
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
