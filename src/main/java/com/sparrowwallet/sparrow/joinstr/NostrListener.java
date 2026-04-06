package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import nostr.api.NIP04;
import nostr.base.PublicKey;
import nostr.client.Client;
import nostr.context.impl.DefaultRequestContext;
import nostr.event.BaseTag;
import nostr.event.Kind;
import nostr.event.impl.Filters;
import nostr.event.impl.GenericEvent;
import nostr.event.message.ReqMessage;
import nostr.event.tag.PubKeyTag;
import nostr.id.Identity;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.net.TorUtils;

import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.Handler;
import java.util.function.Consumer;

public class NostrListener implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(NostrListener.class.getName());
    private static final Logger textLogger = Logger.getLogger("nostr.connection.impl.listeners.TextListener");
    private final Identity identity;
    private final String relay;
    private Client client;
    private Consumer<String> messageHandler;
    private final Map<String, String> poolCredentials;
    private static final ConsoleHandler consoleLogHandler = new ConsoleHandler();
    private transient Handler eventMessageHandler = null;

    public NostrListener(Identity identity, String relay, Map<String, String> poolCredentials) {
        this.identity = identity;
        this.relay = relay;
        this.poolCredentials = poolCredentials;
        setupLogging();
    }

    private static void setupLogging() {
        textLogger.setLevel(Level.INFO);
        consoleLogHandler.setLevel(Level.INFO);
        synchronized (textLogger) {
            if (textLogger.getHandlers().length == 0)
                textLogger.addHandler(consoleLogHandler);
        }
    }

    public void startListening(Consumer<String> messageHandler) {
        this.messageHandler = messageHandler;
        setupEventHandler();
        connectAndSubscribe();
    }

    private void setupEventHandler() {
        eventMessageHandler = new Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                String message = record.getMessage();
                if (message != null && message.contains("WebSocket received: [\"EVENT\"")) {
                    handleEventMessage(message);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        textLogger.addHandler(eventMessageHandler);
    }

    private void handleEventMessage(String message) {
        try {
            int startIndex = message.indexOf("{");
            int endIndex = message.lastIndexOf("}") + 1;
            String eventJson = message.substring(startIndex, endIndex);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode event = mapper.readTree(eventJson);

            String encryptedContent = event.get("content").asText();
            String senderPubkey = event.get("pubkey").asText();
            long timestamp = event.get("created_at").asLong();

            JsonNode tags = event.get("tags");
            String recipientPubkey = null;
            if (tags != null && tags.isArray()) {
                for (JsonNode tag : tags) {
                    if (tag.isArray() && !tag.isEmpty() && "p".equals(tag.get(0).asText())) {
                        recipientPubkey = tag.get(1).asText();
                        break;
                    }
                }
            }

            if (recipientPubkey == null || !recipientPubkey.equals(identity.getPublicKey().toString())) {
                return;
            }

            logger.info("Received encrypted DM from: " + senderPubkey + " to: " + recipientPubkey);
            logger.info("Time: " + new Date(timestamp * 1000));

            try {
                String decryptedContent = NIP04.decrypt(
                        identity,
                        encryptedContent,
                        new PublicKey(senderPubkey));

                if (decryptedContent.contains("\"type\": \"join_pool\"") && poolCredentials != null) {
                    handleJoinRequest(senderPubkey);
                }

                if (messageHandler != null) {
                    messageHandler.accept(decryptedContent);
                }

                logger.info("Successfully decrypted message");
            } catch (Exception e) {
                logger.fine("Failed to decrypt message (may not be for us): " + e.getMessage());
            }
        } catch (Exception e) {
            logger.severe("Error: " + e.getMessage());
        }
    }

    private void handleJoinRequest(String requesterPubkey) {
        if (poolCredentials == null) {
            logger.warning("Received join request but poolCredentials is null - ignoring");
            return;
        }

        try {
            Gson gson = new Gson();
            Map<String, Object> credentialsMap = new LinkedHashMap<>();
            credentialsMap.put("id", poolCredentials.get("id"));
            credentialsMap.put("public_key", poolCredentials.get("public_key"));
            credentialsMap.put("denomination", poolCredentials.get("denomination"));
            credentialsMap.put("peers", poolCredentials.get("peers"));
            credentialsMap.put("timeout", poolCredentials.get("timeout"));

            if (AppServices.isTorRunning()) {
                Client.getInstance().disconnect();
                TorUtils.changeIdentity(AppServices.getTorProxy());
                TorUtils.logTorIp();
            }

            credentialsMap.put("relay", poolCredentials.get("relay"));
            credentialsMap.put("private_key", poolCredentials.get("private_key"));
            credentialsMap.put("fee_rate", poolCredentials.get("fee_rate"));
            String credentialsJson = gson.toJson(credentialsMap);

            List<BaseTag> tags = new ArrayList<>();
            tags.add(new PubKeyTag(new PublicKey(requesterPubkey)));

            NIP04 nip04 = new NIP04(identity, new PublicKey(requesterPubkey));
            String encryptedCredentials = nip04.encrypt(
                    identity,
                    credentialsJson,
                    new PublicKey(requesterPubkey));

            GenericEvent credentialsEvent = new GenericEvent(
                    identity.getPublicKey(),
                    Kind.ENCRYPTED_DIRECT_MESSAGE.getValue(),
                    tags,
                    encryptedCredentials);

            nip04.setEvent(credentialsEvent);
            nip04.sign();
            nip04.send(Map.of("default", relay));

            logger.info("Sent pool credentials to: " + requesterPubkey);
        } catch (Exception e) {
            logger.severe("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void connectAndSubscribe() {
        try {
            if (AppServices.isTorRunning()) {
                Client.getInstance().disconnect();
                TorUtils.changeIdentity(AppServices.getTorProxy());
                TorUtils.logTorIp();
            }

            client = Client.getInstance();
            DefaultRequestContext context = new DefaultRequestContext();
            context.setPrivateKey(identity.getPrivateKey().getRawData());
            context.setRelays(Map.of("default", relay));

            Filters filters = Filters.builder()
                    .kinds(List.of(Kind.ENCRYPTED_DIRECT_MESSAGE))
                    .referencePubKeys(List.of(identity.getPublicKey()))
                    .build();

            String subId = "joinstr-" + System.currentTimeMillis();
            ReqMessage reqMessage = new ReqMessage(subId, filters);

            client.connect(context);
            client.send(reqMessage);

            logger.info("Started listening for encrypted messages on " + relay);
        } catch (Exception e) {
            logger.severe("Error: " + e.getMessage());
            throw new RuntimeException("Failed to start listener", e);
        }
    }

    @Override
    public void close() throws TimeoutException {
        if (eventMessageHandler != null) {
            textLogger.removeHandler(eventMessageHandler);
            eventMessageHandler = null;
        }

        if (client != null) {
            client.disconnect();
            client = null;
            logger.info("Stopped listening for messages");
        }
    }
}