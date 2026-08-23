package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Whether a pool announcement describes a pool this client can actually complete.
 *
 * A pool can require sybil resistance or a transport this client does not implement. Joining one
 * regardless means the join request is rejected or ignored and the user waits until the pool
 * expires with nothing on screen explaining it.
 */
public final class PoolSupport {

    public static final String REQUIRES_VPN =
            "This pool requires a VPN transport. Sparrow is Tor only.";

    public static final String TOR_NOT_ENABLED =
            "This pool does not enable Tor. Sparrow is Tor only.";

    private PoolSupport() {
    }

    /** Why this pool cannot be joined from Sparrow, or null if it can. */
    public static String unsupportedReason(JsonNode poolData) {
        if (poolData == null) {
            return null;
        }

        return transportReason(poolData.get("transport"));
    }

    /**
     * What an aut-ct pool demands of a joiner, or null if it demands nothing.
     *
     * Shown so a user can see what the pool wants even though this client cannot satisfy it. The
     * reference implementation shows the same three fields in its pool list.
     */
    public static String autctRequirement(JsonNode poolData) {
        if (poolData == null || !requiresAutct(poolData)) {
            return null;
        }

        JsonNode autct = poolData.get("autct");
        StringBuilder requirement = new StringBuilder();
        requirement.append(autct.path("min_amount").asLong(0)).append(" sats");
        requirement.append(", ").append(autct.path("min_confirmations").asInt(0)).append(" confs");

        String scriptType = autct.path("script_type").asText("");
        if (!scriptType.isEmpty()) {
            requirement.append(", ").append(scriptType);
        }

        return requirement.toString();
    }

    private static boolean requiresAutct(JsonNode poolData) {
        JsonNode autct = poolData.get("autct");
        if (autct == null || autct.isNull()) {
            return false;
        }

        // the field is only meaningful with a keyset naming the proof requirement
        JsonNode keyset = autct.get("keyset");
        return keyset != null && !keyset.asText("").trim().isEmpty();
    }

    /**
     * Tor is a transport the protocol defines: NIP.md gives the transport object a `tor.enable`
     * flag, and a pool that says nothing is Tor by default. A Tor only client is a full
     * participant, so the only pools refused here are the ones that do not offer Tor at all.
     *
     * Pools advertise transport in two shapes: the object in NIP.md, and the bare string the
     * electrum plugin publishes. Read both.
     */
    private static String transportReason(JsonNode transport) {
        if (transport == null || transport.isNull()) {
            // NIP.md makes tor the default, so an announcement that omits transport is joinable
            return null;
        }

        if (transport.isTextual()) {
            String value = transport.asText("").trim();
            if (value.isEmpty() || value.equalsIgnoreCase("tor")) {
                return null;
            }
            return value.equalsIgnoreCase("vpn") ? REQUIRES_VPN : TOR_NOT_ENABLED;
        }

        if (transport.isObject()) {
            boolean torEnabled = enabled(transport.get("tor"));
            boolean vpnEnabled = enabled(transport.get("vpn"));

            if (torEnabled) {
                return null;
            }
            return vpnEnabled ? REQUIRES_VPN : TOR_NOT_ENABLED;
        }

        return TOR_NOT_ENABLED;
    }

    private static boolean enabled(JsonNode node) {
        return node != null && node.get("enable") != null && node.get("enable").asBoolean(false);
    }
}
