package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.sparrow.AppServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TorUtils {
    private static final Logger log = LoggerFactory.getLogger(TorUtils.class);
    private static final Pattern TOR_OK = Pattern.compile("^2\\d{2}[ -]OK$");
    private static final Pattern TOR_AUTH_METHODS = Pattern
            .compile("^2\\d{2}[ -]AUTH METHODS=(\\S+)\\s?(COOKIEFILE=\"?(.+?)\"?)?$");
    private static final String CHECK_TOR_IP_URL = "https://check.torproject.org/api/ip";

    private static volatile long lastIpCheckMs = 0;
    private static final java.util.concurrent.atomic.AtomicInteger IP_CHECK_COUNTER = new java.util.concurrent.atomic.AtomicInteger(0);

    public static void logTorIp() {
        long now = System.currentTimeMillis();
        if (now - lastIpCheckMs < 30_000) {
            return; // debounce: skip if checked within last 30s
        }
        lastIpCheckMs = now;

        log.info("[TorUtils] Tor circuit rotated, isTorRunning={}", AppServices.isTorRunning());
        Thread ipThread = new Thread(() -> {
            try {
                // Wait up to 60s for Tor to finish bootstrapping
                int waited = 0;
                while (!AppServices.isTorRunning() && waited < 60) {
                    Thread.sleep(2000);
                    waited += 2;
                }
                if (!AppServices.isTorRunning()) {
                    log.warn("[TorUtils] Tor not ready after 60s, IP check aborted");
                    return;
                }
                Thread.sleep(5000);
                HttpClientService httpClientService = AppServices.getHttpClientService();
                if (httpClientService == null) {
                    log.warn("[TorUtils] Tor IP check skipped: HTTP client not available");
                    return;
                }
                Map<String, String> response = httpClientService.requestJson(CHECK_TOR_IP_URL, Map.class, null);
                String ip = response != null ? response.getOrDefault("IP", response.get("ip")) : null;
                if (ip != null) {
                    log.info("[TorUtils] Tor exit IP: {}", ip);
                } else {
                    log.warn("[TorUtils] Tor IP check returned empty response");
                }
            } catch (Exception e) {
                log.warn("[TorUtils] Tor IP check failed: {}", e.toString());
            }
        });
        ipThread.setDaemon(true);
        ipThread.setName("IPChecker-" + IP_CHECK_COUNTER.incrementAndGet());
        ipThread.start();
    }

    public static void changeIdentity(HostAndPort proxy) {
        if (AppServices.isTorRunning()) {
            Tor.getDefault().changeIdentity();
        } else {
            HostAndPort control = HostAndPort.fromParts(proxy.getHost(), proxy.getPort() + 1);
            try (Socket socket = new Socket(control.getHost(), control.getPort())) {
                socket.setSoTimeout(1500);
                if (authenticate(socket)) {
                    writeNewNym(socket);
                }
            } catch (TorAuthenticationException e) {
                log.warn("Error authenticating to Tor at " + control + ", server returned " + e.getMessage());
            } catch (SocketTimeoutException e) {
                log.warn("Timeout reading from " + control + ", is this a Tor ControlPort?");
            } catch (AccessDeniedException e) {
                log.warn("Permission denied reading Tor cookie file at " + e.getFile());
            } catch (FileSystemException e) {
                log.warn("Error reading Tor cookie file at " + e.getFile());
            } catch (Exception e) {
                log.warn("Error connecting to " + control + ", no Tor ControlPort configured?");
            }
        }
    }

    private static boolean authenticate(Socket socket) throws IOException, TorAuthenticationException {
        socket.getOutputStream().write("PROTOCOLINFO\r\n".getBytes());
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String line;
        File cookieFile = null;
        while ((line = reader.readLine()) != null) {
            Matcher authMatcher = TOR_AUTH_METHODS.matcher(line);
            if (authMatcher.matches()) {
                String methods = authMatcher.group(1);
                if (methods.contains("COOKIE") && !authMatcher.group(3).isEmpty()) {
                    cookieFile = new File(authMatcher.group(3));
                }
            }
            if (TOR_OK.matcher(line).matches()) {
                break;
            }
        }

        if (cookieFile != null && cookieFile.exists()) {
            byte[] cookieBytes = Files.readAllBytes(cookieFile.toPath());
            String authentication = "AUTHENTICATE " + Utils.bytesToHex(cookieBytes) + "\r\n";
            socket.getOutputStream().write(authentication.getBytes());
        } else {
            socket.getOutputStream().write("AUTHENTICATE \"\"\r\n".getBytes());
        }

        line = reader.readLine();
        if (TOR_OK.matcher(line).matches()) {
            return true;
        } else {
            throw new TorAuthenticationException(line);
        }
    }

    private static void writeNewNym(Socket socket) throws IOException {
        log.debug("Sending NEWNYM to " + socket);
        socket.getOutputStream().write("SIGNAL NEWNYM\r\n".getBytes());
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String line = reader.readLine();
        if(line == null || !TOR_OK.matcher(line).matches()) {
            log.warn("NEWNYM failed: " + line);
        } else {
            log.info("NEWNYM acknowledged by Tor control port");
        }
    }

    private static class TorAuthenticationException extends Exception {
        public TorAuthenticationException(String message) {
            super(message);
        }
    }
}
