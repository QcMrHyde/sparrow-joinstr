package com.sparrowwallet.sparrow.joinstr;

import nostr.context.impl.DefaultRequestContext;
import nostr.id.Identity;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Publishing must carry the proxy. The nostr api's own send builds a context internally with only
 * the private key and the relay, so an event sent that way leaves by whatever route the JVM
 * defaults to. Since the JVM wide socks property was removed, that is not Tor.
 */
public class PublishProxyTest {

    private static final String RELAY = "wss://nos.lol";

    @Test
    public void thePublishContextCarriesTheProxy() {
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 9050));

        DefaultRequestContext context = JoinstrPublisher.context(
                Identity.generateRandomIdentity(), RELAY, proxy);

        assertSame(proxy, context.getProxy(), "a publish would not go through Tor");
    }

    @Test
    public void thePublishContextCarriesTheRelayAndKey() {
        Identity identity = Identity.generateRandomIdentity();

        DefaultRequestContext context = JoinstrPublisher.context(identity, RELAY, null);

        assertTrue(context.getRelays().containsValue(RELAY));
        assertArrayEquals(identity.getPrivateKey().getRawData(), context.getPrivateKey());
        assertNull(context.getProxy());
    }

    /** Without Tor there is no proxy, and publishing is refused rather than going out in clear. */
    @Test
    public void nothingIsPublishedWithoutTor() {
        JoinstrTransport.setTorRunningForTesting(() -> false);
        try {
            assertNull(JoinstrTransport.proxy());
            assertFalse(JoinstrPublisher.publish(Identity.generateRandomIdentity(), RELAY, null));
        } finally {
            JoinstrTransport.setTorRunningForTesting(null);
        }
    }
}
