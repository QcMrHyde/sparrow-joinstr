package com.sparrowwallet.sparrow.joinstr;

import nostr.client.Client;
import nostr.context.impl.DefaultRequestContext;
import nostr.event.impl.GenericEvent;
import nostr.event.message.EventMessage;
import nostr.id.Identity;

import nostr.event.message.OkMessage;

import java.net.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Sends one signed event on a connection of its own.
 *
 * The nostr api's own {@code send} builds a request context internally, carrying only the private
 * key and the relay, and pushes it through the shared client. That context has no proxy, so
 * publishing that way leaves through whatever route the JVM defaults to rather than through Tor.
 * Building the context here keeps the proxy, and the private connection keeps a publish from
 * disturbing a subscription.
 */
public final class JoinstrPublisher {

    private static final Logger logger = Logger.getLogger(JoinstrPublisher.class.getName());

    /** How long to wait for the relay to acknowledge an event. */
    private static final long ACK_TIMEOUT_MILLIS = 10000;

    private JoinstrPublisher() {
    }

    /** The context a joinstr connection uses, carrying the proxy so the traffic goes over Tor. */
    static DefaultRequestContext context(Identity as, String relay, Proxy proxy) {
        DefaultRequestContext context = new DefaultRequestContext();
        context.setPrivateKey(as.getPrivateKey().getRawData());
        context.setRelays(new LinkedHashMap<>(Map.of("default", relay)));
        context.setProxy(proxy);
        return context;
    }

    /**
     * Publish a signed event and wait for the relay to accept it.
     *
     * {@code Client.send} is fire and forget, so without waiting for the relay's OK a publish that
     * never arrived looks the same as one that did, and the pool stalls with no explanation. The
     * relay's acknowledgement is delivered through the same message listener that carries events.
     */
    public static boolean publish(Identity as, String relay, GenericEvent signedEvent) {
        if (!JoinstrTransport.newCircuit()) {
            logger.warning("Not publishing: tor is not running");
            return false;
        }

        String eventId = signedEvent.getId();
        CountDownLatch acknowledged = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(false);

        Client client = new Client();
        try {
            DefaultRequestContext context = context(as, relay, JoinstrTransport.proxy());
            context.setMessageListener((message, source) -> {
                if (message instanceof OkMessage ok
                        && (eventId == null || eventId.equals(ok.getEventId()))) {
                    accepted.set(Boolean.TRUE.equals(ok.getFlag()));
                    if (!accepted.get()) {
                        logger.warning("Relay rejected an event: " + ok.getMessage());
                    }
                    acknowledged.countDown();
                }
            });

            client.connect(context);
            client.send(new EventMessage(signedEvent));

            if (!acknowledged.await(ACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                logger.warning("Relay did not acknowledge an event within "
                        + (ACK_TIMEOUT_MILLIS / 1000) + "s");
                return false;
            }

            return accepted.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.severe("Failed to publish an event: " + e.getMessage());
            return false;
        } finally {
            try {
                client.disconnect();
            } catch (Exception e) {
                logger.fine("Error closing a publish connection: " + e.getMessage());
            }
        }
    }
}
