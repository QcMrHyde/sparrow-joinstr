package com.sparrowwallet.sparrow.joinstr;

/**
 * The nostr relay this client publishes to and discovers pools on.
 *
 * Traffic for a pool that already exists always goes to the relay named in that pool's
 * announcement. This is only for the two cases where there is no pool to take it from: announcing
 * a new pool, and looking for pools to join.
 */
public final class JoinstrRelay {

    public static final String DEFAULT = "wss://nos.lol";

    private JoinstrRelay() {
    }

    /** The configured relay, or the default when it is unset or not a websocket url. */
    public static String relayOrDefault(String configured) {
        if (configured == null) {
            return DEFAULT;
        }

        String relay = configured.trim();
        if (relay.isEmpty() || !(relay.startsWith("wss://") || relay.startsWith("ws://"))) {
            return DEFAULT;
        }

        return relay;
    }
}
