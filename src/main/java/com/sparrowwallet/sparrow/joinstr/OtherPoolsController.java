package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.net.TorUtils;
import com.sparrowwallet.sparrow.joinstr.control.JoinstrInfoPane;
import com.sparrowwallet.sparrow.joinstr.control.JoinstrPoolList;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import nostr.client.Client;
import nostr.context.impl.DefaultRequestContext;
import nostr.event.Kind;
import nostr.event.impl.Filters;
import nostr.event.impl.GenericEvent;
import nostr.event.message.EventMessage;
import nostr.event.message.ReqMessage;
import nostr.id.Identity;

import java.time.Instant;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class OtherPoolsController extends JoinstrFormController {

    private static final int POOL_REFRESH_TIME = 30000;
    private static final Logger logger = Logger.getLogger(OtherPoolsController.class.getName());

    @FXML
    private VBox contentVBox;

    @FXML
    private TextField searchTextField;

    private JoinstrPoolList joinstrPoolList;
    private JoinstrInfoPane joinstrInfoPane;
    private Label noPoolsLabel;
    private Timer poolRefreshTimer;

    private ArrayList<JoinstrPool> myPools;

    private final AtomicBoolean isFetching = new AtomicBoolean(false);

    /**
     * Read the relay from a pool event, preferring the NIP "relays" array and falling back to the
     * legacy single "relay" field, so pools from other joinstr clients are not silently dropped.
     */
    static String extractRelay(JsonNode poolData) {
        if (poolData.has("relays") && poolData.get("relays").isArray() && poolData.get("relays").size() > 0) {
            return poolData.get("relays").get(0).asText();
        }
        if (poolData.has("relay")) {
            return poolData.get("relay").asText();
        }
        return null;
    }

    /** Legacy pools omit "network"; those are accepted. Otherwise the network must match ours. */
    static boolean networkMatches(JsonNode poolData) {
        if (!poolData.has("network")) {
            return true;
        }
        return poolData.get("network").asText().equalsIgnoreCase(com.sparrowwallet.drongo.Network.get().getName());
    }

    @Override
    public void initializeView() {
        try {
            joinstrPoolList = new JoinstrPoolList();
            joinstrPoolList.configureWithJoinButtons(() -> {
                getJoinstrController().setJoinstrDisplay(JoinstrDisplay.MY_POOLS);
            });

            noPoolsLabel = new Label("No pools found");
            noPoolsLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #666666;");
            noPoolsLabel.setVisible(false);
            noPoolsLabel.setManaged(false);

            joinstrInfoPane = new JoinstrInfoPane();
            joinstrInfoPane.initInfoPane();
            joinstrInfoPane.setVisible(false);
            joinstrInfoPane.setManaged(false);

            joinstrPoolList.setOnPoolSelectedListener(pool -> {
                if (pool != null) {
                    joinstrInfoPane.setVisible(true);
                    joinstrInfoPane.setManaged(true);
                    joinstrInfoPane.updatePoolInfo(pool);
                } else {
                    joinstrInfoPane.setVisible(false);
                    joinstrInfoPane.setManaged(false);
                }
            });

            contentVBox.getChildren().addAll(joinstrPoolList, joinstrInfoPane, noPoolsLabel);

            searchTextField.textProperty().addListener((observable, oldValue, newValue) -> {
                filterPools(newValue);
            });

            refreshView();

        } catch (Exception e) {
            logger.severe("Error initializing view: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void refreshView() {
        myPools = Config.get().getPoolStore();
        startPoolRefresh();
        fetchPools();
    }

    private void startPoolRefresh() {
        if (poolRefreshTimer != null)
            poolRefreshTimer.cancel();
        poolRefreshTimer = new Timer(true);
        poolRefreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                fetchPools();
            }
        }, POOL_REFRESH_TIME, POOL_REFRESH_TIME); // Refresh every 30 seconds
    }

    /**
     * Build a pool from an announcement, or null if it is not one this client should list.
     *
     * The announcement must be signed by the pool key it advertises. Without that check anyone
     * can announce a pool naming someone else's key, and a joiner sends its request to whoever
     * that key belongs to.
     */
    static JoinstrPool parsePool(JsonNode poolData, String authorPubKey) {
        if (poolData == null || !poolData.has("timeout")) {
            return null;
        }

        long timeout = poolData.get("timeout").asLong();
        if (timeout < Instant.now().getEpochSecond()) {
            return null;
        }

        String relayUrl = extractRelay(poolData);
        if (relayUrl == null || relayUrl.isEmpty()
                || !networkMatches(poolData)
                || !poolData.has("public_key")
                || !poolData.has("denomination")
                || !poolData.has("peers")) {
            return null;
        }

        // a pool of one is not a coinjoin, and joining one would just hand the relay a
        // self-spend to watch
        if (poolData.get("peers").asInt(0) < 2) {
            return null;
        }

        String poolKey = poolData.get("public_key").asText();
        if (authorPubKey != null && !authorPubKey.equalsIgnoreCase(poolKey)) {
            logger.warning("Ignoring a pool announcement that is not signed by the key it names");
            return null;
        }

        JoinstrPool pool = new JoinstrPool(
                relayUrl,
                poolKey,
                poolData.get("denomination").asText(),
                poolData.get("peers").asText(),
                String.valueOf(timeout));

        if (poolData.has("fee_rate")) {
            pool.setFeeRate(poolData.get("fee_rate").asText());
        }

        if (poolData.has("id")) {
            pool.setPoolId(poolData.get("id").asText());
        }

        pool.setRequirement(PoolSupport.autctRequirement(poolData));

        String unsupported = PoolSupport.unsupportedReason(poolData);
        pool.setUnsupportedReason(unsupported);
        if (unsupported != null) {
            pool.setStatus("Unsupported");
        }

        return pool;
    }

    private void fetchPools() {
        if (CoinjoinActivity.isActive()) {
            // discovery rotates the tor circuit, which disconnects the shared nostr client and
            // would take the running coinjoin's subscription with it
            logger.info("Skipping pool discovery while a coinjoin is in progress");
            return;
        }

        if (!isFetching.compareAndSet(false, true)) {
            return;
        }

        this.getJoinstrController().submitTask(() -> {
            List<JoinstrPool> pools = new CopyOnWriteArrayList<>();
            ObjectMapper mapper = new ObjectMapper();
            Client client = new Client();

            try {
                if (!JoinstrTransport.newCircuit()) {
                    Platform.runLater(() -> showError(JoinstrTransport.NOT_READY));
                    return;
                }

                Identity identity = Identity.generateRandomIdentity();

                DefaultRequestContext context = new DefaultRequestContext();
                context.setPrivateKey(identity.getPrivateKey().getRawData());
                context.setRelays(new LinkedHashMap<>(Map.of("default",
                        JoinstrRelay.relayOrDefault(Config.get().getNostrRelay()))));
                context.setProxy(JoinstrTransport.proxy());
                context.setMessageListener((message, source) -> {
                    if (!(message instanceof EventMessage eventMessage)
                            || !(eventMessage.getEvent() instanceof GenericEvent event)) {
                        return;
                    }
                    if (event.getKind() == null || event.getKind() != Kind.CONJOIN_POOL.getValue()) {
                        return;
                    }

                    try {
                        JsonNode poolData = mapper.readTree(event.getContent());
                        String author = event.getPubKey() == null ? null : event.getPubKey().toString();
                        JoinstrPool pool = parsePool(poolData, author);
                        if (pool == null) {
                            return;
                        }

                        if (pools.stream().noneMatch(p -> Objects.equals(p.getPubkey(), pool.getPubkey()))
                                && myPools.stream().noneMatch(p -> Objects.equals(p.getPubkey(), pool.getPubkey()))) {
                            pools.add(pool);
                            logger.info("Added a discovered pool");
                            Platform.runLater(() -> updateUIWithPools(new ArrayList<>(pools)));
                        }
                    } catch (Exception e) {
                        logger.warning("Error processing pool event: " + e.getMessage());
                    }
                });

                // Pools are dropped by their own timeout in parsePool, so do not also drop them
                // by the age of the announcement. A pool open for longer than an hour was invisible.
                Filters filters = Filters.builder()
                        .kinds(List.of(Kind.CONJOIN_POOL))
                        .build();

                ReqMessage reqMessage = new ReqMessage("pools-" + System.currentTimeMillis(), filters);

                client.connect(context);
                client.send(reqMessage);

                Thread.sleep(5000);

                Platform.runLater(() -> updateUIWithPools(new ArrayList<>(pools)));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warning("Pool discovery interrupted");
            } catch (Exception e) {
                logger.severe("Error fetching pools: " + e.getMessage());
                Platform.runLater(() -> showError("Failed to fetch pools: " + e.getMessage()));
            } finally {
                try {
                    if (!Thread.currentThread().isInterrupted()) {
                        client.disconnect();
                    }
                } catch (Exception e) {
                    logger.fine("Error disconnecting after discovery: " + e.getMessage());
                }
                isFetching.set(false);
            }
        });
    }

    private void updateUIWithPools(List<JoinstrPool> pools) {
        joinstrPoolList.clearPools();
        if (pools.isEmpty()) {
            noPoolsLabel.setVisible(true);
            noPoolsLabel.setManaged(true);
            joinstrPoolList.setVisible(false);
            joinstrPoolList.setManaged(false);
        } else {
            noPoolsLabel.setVisible(false);
            noPoolsLabel.setManaged(false);
            joinstrPoolList.setVisible(true);
            joinstrPoolList.setManaged(true);
            pools.forEach(joinstrPoolList::addPool);
        }
    }

    private void filterPools(String searchText) {
        joinstrPoolList.filterPools(searchText);
    }

    public void handleSearchButton(ActionEvent e) {
        if (e.getSource() == searchTextField) {
            filterPools(searchTextField.getText());
        }
    }

    @Override
    public void close() throws Exception {
        if (poolRefreshTimer != null) {
            poolRefreshTimer.cancel();
            poolRefreshTimer = null;
        }

        if (joinstrPoolList != null) {
            joinstrPoolList.clearPools();
        }
        joinstrInfoPane = null;
    }
}