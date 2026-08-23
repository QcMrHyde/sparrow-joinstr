package com.sparrowwallet.sparrow.joinstr;

import com.google.gson.Gson;

import nostr.api.NIP04;
import nostr.base.PublicKey;
import nostr.client.Client;
import nostr.context.impl.DefaultRequestContext;
import nostr.event.BaseMessage;
import nostr.event.BaseTag;
import nostr.event.Kind;
import nostr.event.impl.Filters;
import nostr.event.impl.GenericEvent;
import nostr.event.message.EventMessage;
import nostr.event.message.ReqMessage;
import nostr.event.tag.PubKeyTag;
import nostr.id.Identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Receives the encrypted messages addressed to one key on a pool's relay.
 *
 * Events arrive through the nostr client's message listener. This used to work by attaching a
 * handler to the library's own logger and parsing the log lines, which needed every message
 * logged at INFO and broke on any change to the log format.
 */
public class NostrListener implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(NostrListener.class.getName());

    private final Identity identity;
    private final String relay;
    private final Map<String, Object> poolCredentials;

    private Client client;
    private BiConsumer<String, Long> messageHandler;

    public NostrListener(Identity identity, String relay, Map<String, Object> poolCredentials) {
        this.identity = identity;
        this.relay = relay;
        this.poolCredentials = poolCredentials;
    }

    public void startListening(BiConsumer<String, Long> messageHandler) {
        this.messageHandler = messageHandler;
        connectAndSubscribe();
    }

    /** Called for every message the relay sends on this connection. */
    private void onRelayMessage(BaseMessage message) {
        if (!(message instanceof EventMessage eventMessage)) {
            return;
        }

        if (!(eventMessage.getEvent() instanceof GenericEvent event)) {
            return;
        }

        if (event.getKind() == null || event.getKind() != Kind.ENCRYPTED_DIRECT_MESSAGE.getValue()) {
            return;
        }

        if (!isAddressedToUs(event)) {
            return;
        }

        logger.fine("Received an encrypted DM addressed to this key");

        String decryptedContent;
        try {
            decryptedContent = NIP04.decrypt(identity, event.getContent(), event.getPubKey());
        } catch (Exception e) {
            logger.fine("Could not decrypt a message addressed to this key");
            return;
        }

        try {
            if (poolCredentials != null && JoinstrMessage.isJoinRequest(decryptedContent)) {
                handleJoinRequest(event.getPubKey());
            }

            if (messageHandler != null) {
                long createdAt = event.getCreatedAt() == null
                        ? java.time.Instant.now().getEpochSecond()
                        : event.getCreatedAt();
                messageHandler.accept(decryptedContent, createdAt);
            }
        } catch (Exception e) {
            logger.severe("Error handling a pool message: " + e.getMessage());
        }
    }

    private boolean isAddressedToUs(GenericEvent event) {
        if (event.getTags() == null) {
            return false;
        }

        String ours = identity.getPublicKey().toString();
        for (BaseTag tag : event.getTags()) {
            if (tag instanceof PubKeyTag pubKeyTag && pubKeyTag.getPublicKey() != null
                    && ours.equals(pubKeyTag.getPublicKey().toString())) {
                return true;
            }
        }
        return false;
    }

    private void handleJoinRequest(PublicKey requester) {
        if (poolCredentials == null) {
            logger.warning("Received join request but poolCredentials is null - ignoring");
            return;
        }

        try {
            if (!JoinstrTransport.newCircuit()) {
                logger.warning("Not answering a join request: tor is not running");
                return;
            }

            String credentialsJson = new Gson().toJson(poolCredentials);

            List<BaseTag> tags = new ArrayList<>();
            tags.add(new PubKeyTag(requester));

            NIP04 nip04 = new NIP04(identity, requester);
            String encryptedCredentials = nip04.encrypt(identity, credentialsJson, requester);

            GenericEvent credentialsEvent = new GenericEvent(
                    identity.getPublicKey(),
                    Kind.ENCRYPTED_DIRECT_MESSAGE.getValue(),
                    tags,
                    encryptedCredentials);

            nip04.setEvent(credentialsEvent);
            nip04.sign();

            if (!JoinstrPublisher.publish(identity, relay, credentialsEvent)) {
                logger.severe("Failed to send pool credentials");
                return;
            }

            logger.info("Sent pool credentials to a joiner");
        } catch (Exception e) {
            logger.severe("Failed to send pool credentials: " + e.getMessage());
        }
    }

    private void connectAndSubscribe() {
        try {
            if (!JoinstrTransport.newCircuit()) {
                throw new IllegalStateException(JoinstrTransport.NOT_READY);
            }

            client = new Client();

            DefaultRequestContext context = JoinstrPublisher.context(identity, relay,
                    JoinstrTransport.proxy());
            context.setMessageListener((message, source) -> onRelayMessage(message));

            Filters filters = Filters.builder()
                    .kinds(List.of(Kind.ENCRYPTED_DIRECT_MESSAGE))
                    .referencePubKeys(List.of(identity.getPublicKey()))
                    .build();

            ReqMessage reqMessage = new ReqMessage("joinstr-" + System.currentTimeMillis(), filters);

            client.connect(context);
            client.send(reqMessage);

            logger.info("Started listening for encrypted messages");
        } catch (Exception e) {
            logger.severe("Failed to start listener: " + e.getMessage());
            throw new RuntimeException("Failed to start listener", e);
        }
    }

    @Override
    public void close() throws TimeoutException {
        if (client != null) {
            client.disconnect();
            client = null;
            logger.info("Stopped listening for messages");
        }
    }
}
