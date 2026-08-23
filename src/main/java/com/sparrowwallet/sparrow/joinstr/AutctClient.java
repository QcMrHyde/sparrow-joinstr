package com.sparrowwallet.sparrow.joinstr;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Talks to a local aut-ct server.
 *
 * aut-ct proves ownership of a UTXO in a published keyset without saying which one, so a pool can
 * demand one proof per UTXO and make sybil peers expensive. The server speaks a websocket RPC of
 * its own: two binary frames out, a header then the body, and two frames back, an ack then the
 * response.
 */
public class AutctClient {

    private static final Logger logger = Logger.getLogger(AutctClient.class.getName());
    private static final Gson GSON = new Gson();

    public static final String DEFAULT_API_URL = "ws://127.0.0.1:23333";

    /** aut-ct answers prove with 0 and verify with 1 on success. */
    private static final int PROVE_ACCEPTED = 0;
    private static final int VERIFY_ACCEPTED = 1;

    private static final long TIMEOUT_SECONDS = 180;

    private final String apiUrl;

    public AutctClient(String apiUrl) {
        this.apiUrl = apiUrl == null || apiUrl.isBlank() ? DEFAULT_API_URL : apiUrl.trim();
    }

    /** A generated proof and the key image that identifies the UTXO behind it. */
    public record Proof(String proof, String keyImage) {
    }

    /** A verification result. The key image comes from the verifier, never from the sender. */
    public record Verification(boolean valid, String keyImage) {
    }

    /** Whether a server is listening. Opens a connection and closes it without a request. */
    public boolean isReachable() {
        try {
            WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .buildAsync(URI.create(apiUrl), new WebSocket.Listener() {
                    }).get(10, TimeUnit.SECONDS);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "health check");
            return true;
        } catch (Exception e) {
            logger.fine("aut-ct server is not reachable: " + e.getMessage());
            return false;
        }
    }

    /**
     * Prove ownership of the UTXO behind {@code privateKeyWif}.
     *
     * The key is written to a temporary file in aut-ct's own encrypted format, because the server
     * decrypts before parsing and rejects a plaintext WIF.
     */
    public Proof generateProof(String privateKeyWif, String keyset, String context) {
        java.nio.file.Path keyFile = null;
        try {
            String password = AutctKeyFile.randomPassword();
            keyFile = AutctKeyFile.write(privateKeyWif, password);

            JsonObject request = new JsonObject();
            request.addProperty("keyset", context + ":" + keyset);
            request.addProperty("depth", 2);
            request.addProperty("generators_length_log_2", 11);
            request.addProperty("user_label", context);
            request.addProperty("privkey_file_loc", keyFile.toString());
            request.addProperty("bc_network", network());
            request.addProperty("encryption_password", password);

            JsonObject response = call("RPCProver.prove", request);
            if (response == null || accepted(response) != PROVE_ACCEPTED) {
                logger.severe("aut-ct proof generation failed with code " + accepted(response));
                return null;
            }

            String proof = optString(response, "proof");
            String keyImage = optString(response, "key_image");
            if (proof == null || keyImage == null) {
                logger.severe("aut-ct proof is missing its proof or key image");
                return null;
            }

            return new Proof(proof, keyImage);
        } catch (Exception e) {
            logger.severe("aut-ct proof generation error: " + e.getMessage());
            return null;
        } finally {
            AutctKeyFile.delete(keyFile);
        }
    }

    /**
     * Verify a peer's proof.
     *
     * The key image returned here is derived by the verifier. Callers must deduplicate on it and
     * never on anything the peer supplied, or one proof buys every slot in the pool.
     */
    public Verification verifyProof(String proof, String keyset, String context) {
        try {
            JsonObject request = new JsonObject();
            request.addProperty("keyset", context + ":" + keyset);
            request.addProperty("user_label", context);
            request.addProperty("context_label", context);
            request.addProperty("application_label", "autct-v1.0");
            request.addProperty("proof", proof);

            JsonObject response = call("RPCProofVerifier.verify", request);
            if (response == null || accepted(response) != VERIFY_ACCEPTED) {
                logger.warning("aut-ct proof rejected with code " + accepted(response));
                return new Verification(false, null);
            }

            String keyImage = optString(response, "key_image");
            if (keyImage == null) {
                logger.severe("aut-ct accepted a proof but returned no key image");
                return new Verification(false, null);
            }

            return new Verification(true, keyImage);
        } catch (Exception e) {
            logger.severe("aut-ct verification error: " + e.getMessage());
            return new Verification(false, null);
        }
    }

    private static String network() {
        return com.sparrowwallet.drongo.Network.get().getName();
    }

    private static int accepted(JsonObject response) {
        if (response == null || !response.has("accepted") || response.get("accepted").isJsonNull()) {
            return Integer.MIN_VALUE;
        }
        return response.get("accepted").getAsInt();
    }

    private static String optString(JsonObject response, String field) {
        if (!response.has(field) || response.get(field).isJsonNull()) {
            return null;
        }
        String value = response.get(field).getAsString();
        return value.isEmpty() ? null : value;
    }

    /** One request: a header frame, then the body, then an ack frame and the response. */
    JsonObject call(String method, JsonObject body) throws Exception {
        List<String> received = new ArrayList<>();
        CompletableFuture<Void>answered = new CompletableFuture<>();

        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .buildAsync(URI.create(apiUrl), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                        buffer.append(StandardCharsets.UTF_8.decode(data));
                        if (last) {
                            received.add(buffer.toString());
                            buffer.setLength(0);
                            if (received.size() >= 2) {
                                answered.complete(null);
                            }
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            received.add(buffer.toString());
                            buffer.setLength(0);
                            if (received.size() >= 2) {
                                answered.complete(null);
                            }
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        answered.completeExceptionally(error);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                        answered.completeExceptionally(new IllegalStateException("aut-ct closed the connection"));
                        return null;
                    }
                }).get(20, TimeUnit.SECONDS);

        try {
            socket.sendBinary(ByteBuffer.wrap(header(method).getBytes(StandardCharsets.UTF_8)), true).get();
            socket.sendBinary(ByteBuffer.wrap(GSON.toJson(body).getBytes(StandardCharsets.UTF_8)), true).get();

            answered.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            } catch (Exception e) {
                logger.fine("Error closing the aut-ct connection: " + e.getMessage());
            }
        }

        // The first frame only acknowledges the request and the second carries the answer, but
        // an error can arrive as a bare string, so take the last frame that is an object.
        for (int i = received.size() - 1; i >= 0; i--) {
            try {
                JsonObject parsed = GSON.fromJson(received.get(i), JsonObject.class);
                if (parsed != null && parsed.has("accepted")) {
                    return parsed;
                }
            } catch (Exception e) {
                // not this frame
            }
        }

        logger.warning("aut-ct returned no recognisable response: " + received);
        return null;
    }

    static String header(String method) {
        return "{\"Request\": {\"id\": 0, \"service_method\": \"" + method
                + "\",\"timeout\": {\"secs\": 120,\"nanos\": 0}}}\n";
    }
}
