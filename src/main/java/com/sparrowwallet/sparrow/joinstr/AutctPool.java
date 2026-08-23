package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The aut-ct requirement a pool can advertise.
 *
 * A keyset name carries the requirement in itself:
 * {@code autct-<height>-<min amount>-<min confirmations>-<depth>-<branching>.aks}. The name also
 * reaches an autct server as a command argument, so only that exact shape is accepted.
 */
public final class AutctPool {

    private static final Pattern KEYSET = Pattern.compile("^autct-(\\d+)-(\\d+)-(\\d+)-(\\d+)-(\\d+)\\.aks$");

    private AutctPool() {
    }

    /** Whether this is a keyset name an autct server will accept as an argument. */
    public static boolean isValidKeyset(String keyset) {
        return keyset != null && KEYSET.matcher(keyset.trim()).matches();
    }

    /** The announcement's autct object for a keyset, or null if the name is not one. */
    public static String announcementJson(String keyset) {
        if (!isValidKeyset(keyset)) {
            return null;
        }

        Matcher matcher = KEYSET.matcher(keyset.trim());
        if (!matcher.matches()) {
            return null;
        }

        long minAmount = Long.parseLong(matcher.group(2));
        int minConfirmations = Integer.parseInt(matcher.group(3));

        return "{\"keyset\": \"" + keyset.trim() + "\", \"min_amount\": " + minAmount
                + ", \"min_confirmations\": " + minConfirmations + ", \"script_type\": \"p2tr\"}";
    }

    /** The keyset a pool demands, or null if it demands none. */
    public static String keysetOf(JsonNode poolData) {
        if (poolData == null) {
            return null;
        }

        JsonNode autct = poolData.get("autct");
        if (autct == null || autct.isNull()) {
            return null;
        }

        String keyset = autct.path("keyset").asText("").trim();
        return isValidKeyset(keyset) ? keyset : null;
    }

    public static long minAmount(JsonNode poolData) {
        JsonNode autct = poolData == null ? null : poolData.get("autct");
        return autct == null ? 0 : autct.path("min_amount").asLong(0);
    }

    public static int minConfirmations(JsonNode poolData) {
        JsonNode autct = poolData == null ? null : poolData.get("autct");
        return autct == null ? 0 : autct.path("min_confirmations").asInt(0);
    }

    /**
     * The context a proof is bound to.
     *
     * It is the hash of the pool id together with the pool key, not the id alone, so a proof
     * published in one pool cannot be reused by an announcement that copies its id. The value is
     * hex because it reaches the autct server as a command argument.
     */
    public static String context(String poolId, String poolPublicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(((poolId == null ? "" : poolId) + (poolPublicKey == null ? "" : poolPublicKey))
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
