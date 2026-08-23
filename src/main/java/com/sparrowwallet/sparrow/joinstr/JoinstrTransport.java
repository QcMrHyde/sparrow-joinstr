package com.sparrowwallet.sparrow.joinstr;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.net.TorUtils;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * The transport every joinstr request goes over.
 *
 * Publishing a join request, an output address or a signed input from the wallet's real IP links
 * that IP to the coinjoin. Each of those used to check whether Tor was running and simply carry on
 * in the clear when it was not, so a slow or failed Tor bootstrap silently downgraded the join
 * rather than stopping it.
 */
public final class JoinstrTransport {

    private static final Logger logger = Logger.getLogger(JoinstrTransport.class.getName());

    public static final String NOT_READY =
            "Tor is not running, so this request would go out from your real IP address.\n\n"
                    + "Joinstr requires Tor. Wait for it to finish starting, or check the Tor "
                    + "settings, and try again.";

    private static BooleanSupplier torRunning = AppServices::isTorRunning;

    private JoinstrTransport() {
    }

    public static boolean isReady() {
        return torRunning.getAsBoolean();
    }

    /**
     * The proxy joinstr connections go through, or null when there is none.
     *
     * This is handed to each nostr connection rather than set as a JVM wide system property, so
     * unrelated Sparrow traffic is unaffected.
     */
    public static Proxy proxy() {
        if (directForTesting) {
            return null;
        }

        if (!isReady()) {
            return null;
        }

        HostAndPort torProxy = AppServices.getTorProxy();
        if (torProxy == null) {
            return null;
        }

        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(torProxy.getHost(), torProxy.getPort()));
    }

    /** Why this request must not be sent, or null if it may be. */
    public static String unavailableReason() {
        return isReady() ? null : NOT_READY;
    }

    /**
     * Take a fresh circuit for the next request. Returns false when Tor is not available, in which
     * case the caller must not send anything.
     *
     * Each joinstr event goes out on its own circuit so the relay cannot tie one peer's output
     * registration to its input registration by source address. Rotating does not disturb
     * connections that are already open: a new circuit is picked up by the next connection, and
     * every publish opens its own.
     */
    public static boolean newCircuit() {
        if (directForTesting) {
            return true;
        }

        if (!isReady()) {
            logger.warning("Refusing to send a joinstr request: tor is not running");
            return false;
        }

        HostAndPort proxy = AppServices.getTorProxy();
        if (proxy == null) {
            logger.warning("Refusing to send a joinstr request: no tor proxy is configured");
            return false;
        }

        TorUtils.changeIdentity(proxy);
        TorUtils.logTorIp();
        return true;
    }

    /** Lets a test stand in for the running tor instance. */
    static void setTorRunningForTesting(BooleanSupplier supplier) {
        torRunning = supplier == null ? AppServices::isTorRunning : supplier;
    }

    /**
     * Lets an integration test talk to a local relay with no tor in the way.
     *
     * Production always goes through {@link #proxy()}; this only exists so the message flow can
     * be exercised without a tor daemon.
     */
    static void setDirectForTesting(boolean direct) {
        directForTesting = direct;
    }

    private static boolean directForTesting = false;
}
