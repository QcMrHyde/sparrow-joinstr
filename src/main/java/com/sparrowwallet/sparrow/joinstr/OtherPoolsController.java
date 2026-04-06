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
import nostr.event.message.ReqMessage;
import nostr.id.Identity;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OtherPoolsController extends JoinstrFormController {

    private static final int POOL_REFRESH_TIME = 30000;
    private static final Logger logger = Logger.getLogger(OtherPoolsController.class.getName());
    private static final String DEFAULT_RELAY = "wss://nos.lol";
    private static final Logger textLogger = Logger.getLogger("nostr.connection.impl.listeners.TextListener");

    @FXML
    private VBox contentVBox;

    @FXML
    private TextField searchTextField;

    private JoinstrPoolList joinstrPoolList;
    private JoinstrInfoPane joinstrInfoPane;
    private Label noPoolsLabel;
    private Timer poolRefreshTimer;

    private ArrayList<JoinstrPool> myPools;

    private java.util.logging.Handler currentEventHandler;
    private final AtomicBoolean isFetching = new AtomicBoolean(false);

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

    private void fetchPools() {

        if (!isFetching.compareAndSet(false, true)) {
            return;
        }

        this.getJoinstrController().submitTask(() -> {
            try {
                textLogger.setLevel(Level.INFO);

                if (currentEventHandler != null) {
                    textLogger.removeHandler(currentEventHandler);
                }

                List<JoinstrPool> pools = new CopyOnWriteArrayList<>();
                ObjectMapper mapper = new ObjectMapper();

                currentEventHandler = new java.util.logging.Handler() {
                    @Override
                    public void publish(java.util.logging.LogRecord record) {
                        String message = record.getMessage();
                        if (message != null && message.contains("WebSocket received: [\"EVENT\"")) {
                            try {
                                int startIndex = message.indexOf("{");
                                int endIndex = message.lastIndexOf("}") + 1;
                                if (startIndex >= 0 && endIndex > startIndex) {
                                    String eventJson = message.substring(startIndex, endIndex);
                                    JsonNode event = mapper.readTree(eventJson);

                                    if (event.has("content")) {
                                        JsonNode poolData = mapper.readTree(event.get("content").asText());

                                        if (poolData.has("timeout")) {
                                            long timeout = poolData.get("timeout").asLong();
                                            if (timeout < Instant.now().getEpochSecond()) {
                                                return;
                                            }

                                            JoinstrPool pool = new JoinstrPool(
                                                    poolData.get("relay").asText(),
                                                    poolData.get("public_key").asText(),
                                                    poolData.get("denomination").asText(),
                                                    poolData.get("peers").asText(),
                                                    String.valueOf(timeout));

                                            if (pools.stream().noneMatch(
                                                    (p) -> Objects.equals(p.getPubkey(), pool.getPubkey())) &&
                                                    myPools.stream().noneMatch(
                                                            (p) -> Objects.equals(p.getPubkey(), pool.getPubkey()))) {

                                                pools.add(pool);
                                                logger.info("Added pool: " + pool.getRelay() + " - "
                                                        + pool.getDenomination());
                                                Platform.runLater(() -> updateUIWithPools(new ArrayList<>(pools)));

                                            }

                                        }
                                    }
                                }
                            } catch (Exception e) {
                                logger.warning("Error processing pool event: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void flush() {
                    }

                    @Override
                    public void close() {
                    }
                };

                textLogger.addHandler(currentEventHandler);

                Identity identity = Identity.generateRandomIdentity();

                Filters filters = Filters.builder()
                        .kinds(List.of(Kind.CONJOIN_POOL))
                        .since(System.currentTimeMillis() / 1000 - 3600) // Last hour
                        .build();

                String subId = "pools-" + System.currentTimeMillis();
                ReqMessage reqMessage = new ReqMessage(subId, filters);

                Client client = Client.getInstance();
                DefaultRequestContext context = new DefaultRequestContext();
                context.setPrivateKey(identity.getPrivateKey().getRawData());
                context.setRelays(Map.of("default", DEFAULT_RELAY));

                try {
                    if (AppServices.isTorRunning()) {
                        Client.getInstance().disconnect();
                        TorUtils.changeIdentity(AppServices.getTorProxy());
                    }

                    client.connect(context);
                    client.send(reqMessage);

                    Thread.sleep(5000);

                    textLogger.removeHandler(currentEventHandler);
                    currentEventHandler = null;

                    Platform.runLater(() -> updateUIWithPools(new ArrayList<>(pools)));

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warning("Sleep interrupted: " + e.getMessage());
                } finally {
                    if (!Thread.currentThread().isInterrupted())
                        client.disconnect();
                    isFetching.set(false);
                }

            } catch (Exception e) {
                logger.severe("Error fetching pools: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> showError("Failed to fetch pools: " + e.getMessage()));
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
        if (currentEventHandler != null) {
            textLogger.removeHandler(currentEventHandler);
            currentEventHandler = null;
        }

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