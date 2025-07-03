package com.sparrowwallet.sparrow.joinstr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public class NostrWebSocketClient {

    private WebSocket webSocket;
    private WebSocketListener webSocketListener;

    public void connect(String relayUrl) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            WebSocket.Builder builder = client.newWebSocketBuilder();

            webSocketListener = new WebSocketListener();

            webSocket = builder.buildAsync(
                    URI.create(relayUrl),
                    webSocketListener
            ).join();

        } catch (CompletionException e) {
            System.err.println("Failed to connect: " + e.getMessage());
        }
    }

    private String sendMessage(String message) {

        String result = "";

        try {
            webSocket.sendText(message, true);
            while(result.isEmpty()) {   // TODO: There's probably a better way
                Thread.sleep(10);
                result = webSocketListener.getLastData();
            }
        } catch (Exception e) {
            if(e != null) {}
        }
        return result;
    }

    public void requestPoolsEvent(String nostrEventId) {
        try {

                String message = "[\"REQ\", \"pool-event\", {\"#e\":[\"" + nostrEventId + "\"]}]";
                String eventJson = sendMessage(message);

                if(!eventJson.isEmpty()) {
                    // [ "EOSE", "pool-event" ]
                }

            } catch (Exception e) {
                if(e != null) {
                    e.printStackTrace();
                }
            }

        }

    // WebSocket event listener implementation
    private static class WebSocketListener implements WebSocket.Listener {

        private String lastData = "";

        public String getLastData() {
            String result = lastData;
            lastData = "";
            return result;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            System.out.println("WebSocket connection opened");
            webSocket.request(1); // Request one message
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            System.out.println("Received text message: " + data);
            lastData = data.toString();
            webSocket.request(1); // Request next message
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            System.out.println("Received binary message, size: " + data.remaining());
            webSocket.request(1); // Request next message
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            System.out.println("Received ping");
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            System.out.println("Received pong");
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("WebSocket closed with status: " + statusCode + ", reason: " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("WebSocket error: " + error.getMessage());
            error.printStackTrace();
        }
    }
}
