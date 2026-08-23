package com.sparrowwallet.sparrow.joinstr;

import nostr.client.Client;
import nostr.context.impl.DefaultRequestContext;
import nostr.event.impl.GenericEvent;
import nostr.event.message.EventMessage;
import nostr.id.Identity;

import java.net.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * How long to let a send drain before closing the connection.
     *
     * {@code Client.send} is fire and forget with no completion signal, so closing immediately can
     * drop the message on the floor.
     */
    private static final long FLUSH_MILLIS = 750;

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

    /** Publish a signed event. Returns false if it could not be sent. */
    public static boolean publish(Identity as, String relay, GenericEvent signedEvent) {
        if (!JoinstrTransport.newCircuit()) {
            logger.warning("Not publishing: tor is not running");
            return false;
        }

        Client client = new Client();
        try {
            client.connect(context(as, relay, JoinstrTransport.proxy()));
            client.send(new EventMessage(signedEvent));

            Thread.sleep(FLUSH_MILLIS);
            return true;
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
