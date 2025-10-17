package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowwallet.sparrow.io.Config;

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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OtherPoolsController extends JoinstrPoolListFormController {
    private static final Logger logger = Logger.getLogger(OtherPoolsController.class.getName());
    private static final String DEFAULT_RELAY = "wss://nos.lol";

    @FXML
    private VBox contentVBox;

    @FXML
    private TextField searchTextField;

    private Label infoPoolsLabel;
    private Timer poolRefreshTimer;

    private ArrayList<JoinstrPool> myPools;

    @Override
    public void initializeView() {
        try {
            super.initializeView();
            joinstrPoolList.filterCreatedPools();
            joinstrPoolList.configureWithJoinButtons();
            joinstrPoolList.setVisible(false);
            joinstrPoolList.setManaged(false);

            infoPoolsLabel = new Label("Looking for pools...");
            infoPoolsLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #666666;");

            contentVBox.getChildren().addAll(joinstrPoolList, joinstrInfoPane, infoPoolsLabel);

            searchTextField.textProperty().addListener((observable, oldValue, newValue) -> {
                joinstrPoolList.filterPools(newValue);
            });

            myPools = Config.get().getPoolStore();

            startPoolRefresh();

            fetchPools();

        } catch (Exception e) {
            if(e != null) {
                logger.severe("Error initializing view: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void startPoolRefresh() {
        poolRefreshTimer = new Timer(true);
        poolRefreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                fetchPools();
            }
        }, 30000, 30000); // Refresh every 30 seconds
    }

    private void fetchPools() {
        new Thread(() -> {
            try {
                Logger textLogger = Logger.getLogger("nostr.connection.impl.listeners.TextListener");
                textLogger.setLevel(Level.INFO);
                ConsoleHandler handler = new ConsoleHandler();
                handler.setLevel(Level.INFO);
                textLogger.addHandler(handler);

                List<JoinstrPool> pools = new CopyOnWriteArrayList<>();
                ObjectMapper mapper = new ObjectMapper();

                java.util.logging.Handler eventHandler = new java.util.logging.Handler() {
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

                                            String formattedTimeout = Instant.ofEpochSecond(timeout)
                                                    .atZone(ZoneOffset.UTC)
                                                    .format(DateTimeFormatter.ofPattern("HH:mm:ss z"));

                                            JoinstrPool pool = new JoinstrPool(
                                                    poolData.get("relay").asText(),
                                                    poolData.get("public_key").asText(),
                                                    poolData.get("denomination").asText() + " BTC",
                                                    "0/" + poolData.get("peers").asText(),
                                                    formattedTimeout
                                            );

                                            if(pools.stream().noneMatch((p) -> Objects.equals(p.getPubkey(), pool.getPubkey())) &&
                                               myPools.stream().noneMatch((p) -> Objects.equals(p.getPubkey(), pool.getPubkey()))) {

                                                pools.add(pool);
                                                logger.info("Added pool: " + pool.getRelay() + " - " + pool.getDenomination());
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
                    public void flush() {}

                    @Override
                    public void close() {}
                };

                textLogger.addHandler(eventHandler);

                Identity identity = Identity.generateRandomIdentity();

                Filters filters = Filters.builder()
                        .kinds(List.of(Kind.CONJOIN_POOL))
                        .since(System.currentTimeMillis()/1000 - 3600) // Last hour
                        .build();

                String subId = "pools-" + System.currentTimeMillis();
                ReqMessage reqMessage = new ReqMessage(subId, filters);

                Client client = Client.getInstance();
                DefaultRequestContext context = new DefaultRequestContext();
                context.setPrivateKey(identity.getPrivateKey().getRawData());
                context.setRelays(Map.of("default", DEFAULT_RELAY));

                client.connect(context);
                client.send(reqMessage);

                Thread.sleep(5000);

                client.disconnect();

                textLogger.removeHandler(eventHandler);

                Platform.runLater(() -> updateUIWithPools(new ArrayList<>(pools)));

            } catch (Exception e) {
                logger.severe("Error fetching pools: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> showError("Failed to fetch pools: " + e.getMessage()));
            }
        }).start();
    }

    private void updateUIWithPools(List<JoinstrPool> pools) {
        joinstrPoolList.clearPools();
        if (pools.isEmpty()) {
            infoPoolsLabel.setText("No pools found");
            infoPoolsLabel.setVisible(true);
            infoPoolsLabel.setManaged(true);
            joinstrPoolList.setVisible(false);
            joinstrPoolList.setManaged(false);
        } else {
            infoPoolsLabel.setVisible(false);
            infoPoolsLabel.setManaged(false);
            joinstrPoolList.setVisible(true);
            joinstrPoolList.setManaged(true);
            pools.forEach(joinstrPoolList::addPool);
        }
    }

    public void handleSearchButton(ActionEvent e) {
        if(e.getSource() == searchTextField) {
            joinstrPoolList.filterPools(searchTextField.getText());
        }
    }

}