package com.sparrowwallet.sparrow.joinstr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The selected outpoint together with the registered output addresses is exactly the linkage a
 * coinjoin exists to break. Writing both into one log file hands it to anyone who reads that file,
 * or to anyone the user sends it to when reporting a bug.
 */
public class LogLinkageTest {

    private static final String PEER_ADDRESS = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq";
    private static final String OWN_ADDRESS = "bc1q9d4ywgfnd8h43da5tpcxcn6ajv590cg6d3tg6axemvljvt2k76zs50tv4q";

    private final List<String> logged = new ArrayList<>();
    private final List<Logger> attached = new ArrayList<>();
    private Handler capture;

    @BeforeEach
    public void captureJoinstrLogs() {
        capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        for(Class<?> type : new Class<?>[] {CoinjoinHandler.class, JoinPoolHandler.class, NostrListener.class}) {
            Logger logger = Logger.getLogger(type.getName());
            logger.setLevel(Level.ALL);
            logger.addHandler(capture);
            attached.add(logger);
        }
    }

    @AfterEach
    public void detach() {
        for(Logger logger : attached) {
            logger.removeHandler(capture);
        }
    }

    private void assertNothingLogged(String secret, String what) {
        assertFalse(logged.isEmpty(), "expected something to be logged");
        for(String message : logged) {
            assertFalse(message.contains(secret), what + " was written to the log: " + message);
        }
    }

    private CoinjoinHandler handler() {
        JoinstrPool pool = new JoinstrPool("wss://nos.lol", "pk", "0.001", "3", "1750000000");
        return new CoinjoinHandler(null, pool, null, null, status -> {
        });
    }

    @Test
    public void aPeerOutputAddressIsNotLogged() {
        handler().handleDecryptedMessage(
                "{\"type\":\"output\",\"address\":\"" + PEER_ADDRESS + "\"}");

        assertNothingLogged(PEER_ADDRESS, "a peer's output address");
        assertTrue(logged.stream().anyMatch(m -> m.contains("Received output 1/3")),
                "the output should still be counted in the log: " + logged);
    }

    @Test
    public void aRejectedOutputAddressIsNotEchoed() {
        handler().handleDecryptedMessage("{\"type\":\"output\",\"address\":\"not-a-valid-address\"}");

        assertNothingLogged("not-a-valid-address", "a rejected address");
    }

    @Test
    public void ownOutputAddressIsNotLoggedWhenItIsRejected() {
        handler().startOutputPhase("definitely-not-an-address");

        assertNothingLogged("definitely-not-an-address", "the wallet's own address");
    }

    @Test
    public void severalPeerOutputsAreCountedWithoutNamingAny() {
        CoinjoinHandler handler = handler();

        handler.handleDecryptedMessage("{\"type\":\"output\",\"address\":\"" + PEER_ADDRESS + "\"}");
        handler.handleDecryptedMessage("{\"type\":\"output\",\"address\":\"" + OWN_ADDRESS + "\"}");

        assertNothingLogged(PEER_ADDRESS, "a peer's output address");
        assertNothingLogged(OWN_ADDRESS, "a peer's output address");
        assertTrue(logged.stream().anyMatch(m -> m.contains("2/3")), logged.toString());
    }

    /**
     * A backstop for the paths that need a wallet and a relay to reach, which the cases above
     * cannot drive. Walks the joinstr sources and reassembles each logger statement across wrapped
     * lines, so a value interpolated on a continuation line is still seen.
     */
    @Test
    public void noLoggerCallInterpolatesAnAddressOrOutpoint() throws IOException {
        Path dir = Paths.get(System.getProperty("user.dir"),
                "src", "main", "java", "com", "sparrowwallet", "sparrow", "joinstr");
        assumeTrue(Files.isDirectory(dir), "joinstr sources not found at " + dir);

        String[] sensitive = {"getHash()", "getOutpoint()", "getDerivationPath()",
                "myOutputAddress", "addressStr", "senderPubkey", "recipientPubkey"};

        List<String> offenders = new ArrayList<>();
        try(Stream<Path> files = Files.walk(dir)) {
            for(Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                for(String statement : loggerStatements(file)) {
                    for(String value : sensitive) {
                        if(statement.contains(value)) {
                            offenders.add(file.getFileName() + ": " + statement);
                        }
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "logger calls must not interpolate an address, outpoint or derivation path:\n"
                        + String.join("\n", offenders));
    }

    private List<String> loggerStatements(Path file) throws IOException {
        List<String> statements = new ArrayList<>();
        StringBuilder current = null;

        for(String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if(current == null) {
                if(trimmed.startsWith("logger.info(") || trimmed.startsWith("logger.warning(")
                        || trimmed.startsWith("logger.severe(") || trimmed.startsWith("log.info(")
                        || trimmed.startsWith("log.warn(")) {
                    current = new StringBuilder(trimmed);
                } else {
                    continue;
                }
            } else {
                current.append(' ').append(trimmed);
            }

            if(current.toString().endsWith(");")) {
                statements.add(current.toString());
                current = null;
            }
        }
        return statements;
    }
}
