package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.Handler;
import java.util.function.Consumer;

public class NostrListener {
    private static final Logger logger = Logger.getLogger(NostrListener.class.getName());
    private final Identity identity;
    private final String relay;
    private Client client;
    private Consumer<String> messageHandler;
    private final Map<String, String> poolCredentials;

    public NostrListener(Identity identity, String relay, Map<String, String> poolCredentials) {
        this.identity = identity;
        this.relay = relay;
        this.poolCredentials = poolCredentials;
        setupLogging();
    }

    private void setupLogging() {
        Logger textLogger = Logger.getLogger("nostr.connection.impl.listeners.TextListener");
        textLogger.setLevel(Level.INFO);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.INFO);
        textLogger.addHandler(handler);
    }

    public void startListening(Consumer<String> messageHandler) {
        this.messageHandler = messageHandler;
        setupEventHandler();
        connectAndSubscribe();
    }

    private void setupEventHandler() {
        Logger textLogger = Logger.getLogger("nostr.connection.impl.listeners.TextListener");
        textLogger.addHandler(new Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                String message = record.getMessage();
                if (message.contains("WebSocket received: [\"EVENT\"")) {
                    handleEventMessage(message);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
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
                    if (tag.isArray() && tag.size() > 0 && "p".equals(tag.get(0).asText())) {
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
                        new PublicKey(senderPubkey)
                );

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
            logger.severe("Error handling event message: " + e.getMessage());
        }
    }

    private void handleJoinRequest(String requesterPubkey) {
        if (poolCredentials == null) {
            logger.warning("Received join request but poolCredentials is null - ignoring");
            return;
        }

        try {
            String credentialsJson = String.format(
                    "{\n" +
                            "  \"id\": \"%s\",\n" +
                            "  \"public_key\": \"%s\",\n" +
                            "  \"denomination\": %s,\n" +
                            "  \"peers\": %s,\n" +
                            "  \"timeout\": %s,\n" +
                            "  \"relay\": \"%s\",\n" +
                            "  \"private_key\": \"%s\",\n" +
                            "  \"fee_rate\": %s\n" +
                            "}",
                    poolCredentials.get("id"),
                    poolCredentials.get("public_key"),
                    poolCredentials.get("denomination"),
                    poolCredentials.get("peers"),
                    poolCredentials.get("timeout"),
                    poolCredentials.get("relay"),
                    poolCredentials.get("private_key"),
                    poolCredentials.get("fee_rate")
            );

            List<BaseTag> tags = new ArrayList<>();
            tags.add(new PubKeyTag(new PublicKey(requesterPubkey)));

            NIP04 nip04 = new NIP04(identity, new PublicKey(requesterPubkey));
            String encryptedCredentials = nip04.encrypt(
                    identity,
                    credentialsJson,
                    new PublicKey(requesterPubkey)
            );

            GenericEvent credentialsEvent = new GenericEvent(
                    identity.getPublicKey(),
                    Kind.ENCRYPTED_DIRECT_MESSAGE.getValue(),
                    tags,
                    encryptedCredentials
            );

            nip04.setEvent(credentialsEvent);
            nip04.sign();
            nip04.send(Map.of("default", relay));

            logger.info("Sent pool credentials to: " + requesterPubkey);
        } catch (Exception e) {
            logger.severe("Failed to send pool credentials: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void connectAndSubscribe() {
        try {
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
            logger.severe("Failed to connect and subscribe: " + e.getMessage());
            throw new RuntimeException("Failed to start listener", e);
        }
    }

    public void stop() throws TimeoutException {
        if (client != null) {
            client.disconnect();
            logger.info("Stopped listening for messages");
        }
    }

    public CompletableFuture<String> waitForJoinRequest(long timeoutMillis) {
        CompletableFuture<String> future = new CompletableFuture<>();

        Consumer<String> handler = decryptedMessage -> {
            if (decryptedMessage.contains("\"type\": \"join_pool\"")) {
                future.complete(decryptedMessage);
            }
        };

        startListening(handler);

        CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS).execute(() -> {
            if (!future.isDone()) {
                future.completeExceptionally(new TimeoutException("No join request received within timeout"));
                try {
                    stop();
                } catch (TimeoutException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        return future;
    }
}