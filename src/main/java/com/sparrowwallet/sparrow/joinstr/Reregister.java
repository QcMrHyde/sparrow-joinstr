package com.sparrowwallet.sparrow.joinstr;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import com.sparrowwallet.drongo.address.Address;

import org.bouncycastle.util.encoders.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The phase 3 re-registration message.
 *
 * When a pool loses a peer during input registration, the peers that did register claim an output
 * again, this time proving ring membership rather than naming themselves. The pool keeps one
 * output per key image, so a peer cannot claim two.
 */
public final class Reregister {

    private static final Gson GSON = new Gson();

    private Reregister() {
    }

    /** The message a re-registration signs: the claimed address bound to this pool. */
    public static byte[] message(String address, String poolId) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((address + poolId).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Build the payload published to the pool. */
    public static String build(String address, String poolId, Lsag.Signature signature) {
        JsonObject sig = new JsonObject();
        sig.addProperty("Y0", signature.getY0());
        sig.add("S", GSON.toJsonTree(signature.getS()));
        sig.add("C", GSON.toJsonTree(signature.getC()));

        JsonObject payload = new JsonObject();
        payload.addProperty("version", JoinstrMessage.VERSION);
        payload.addProperty("type", "reregister");
        payload.addProperty("address", address);
        payload.addProperty("sig", Base64.toBase64String(GSON.toJson(sig).getBytes(StandardCharsets.UTF_8)));
        payload.addProperty("key_image", signature.getY0());
        payload.addProperty("pool_id", poolId);

        return GSON.toJson(payload);
    }

    /** An accepted re-registration: the address claimed, and the key image that claimed it. */
    public record Accepted(String address, String keyImage) {
    }

    /**
     * Check one re-registration, returning what to accept or null to skip.
     *
     * The key image compared is the one inside the verified signature, never the {@code key_image}
     * field beside it. That field is the sender's claim; the signature's own Y0 is bound to the
     * signing key, so dedup has to use it or one ring member varies the field and claims an output
     * for every peer.
     */
    public static Accepted validate(String decrypted, String poolId, List<String> ringPubKeys,
            Collection<String> claimedAddresses, Collection<String> seenKeyImages) {
        try {
            JsonObject payload = GSON.fromJson(decrypted, JsonObject.class);
            if (payload == null || !payload.has("type")
                    || !"reregister".equals(payload.get("type").getAsString())) {
                return null;
            }

            String address = payload.get("address").getAsString();
            try {
                Address.fromString(address);
            } catch (Exception e) {
                return null;
            }

            JsonObject sig = GSON.fromJson(
                    new String(Base64.decode(payload.get("sig").getAsString()), StandardCharsets.UTF_8),
                    JsonObject.class);

            List<String> s = new ArrayList<>();
            sig.getAsJsonArray("S").forEach(element -> s.add(element.getAsString()));
            List<String> c = new ArrayList<>();
            sig.getAsJsonArray("C").forEach(element -> c.add(element.getAsString()));

            Lsag.Signature signature = new Lsag.Signature(sig.get("Y0").getAsString(), s, c);

            if (!Lsag.verify(message(address, poolId), signature, ringPubKeys)) {
                return null;
            }

            String keyImage = signature.getY0();
            if (seenKeyImages.contains(keyImage) || claimedAddresses.contains(address)) {
                return null;
            }

            return new Accepted(address, keyImage);
        } catch (Exception e) {
            return null;
        }
    }
}
