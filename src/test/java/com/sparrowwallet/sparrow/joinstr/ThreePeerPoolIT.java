package com.sparrowwallet.sparrow.joinstr;

import nostr.id.Identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Three peers exchanging real messages through a real relay.
 *
 * Everything below the wallet is the production path: NIP-04 encryption, the nostr client, the
 * message listener that replaced the log scraping, and one connection per publish. Requires a
 * relay on ws://127.0.0.1:7777, so it is opt in through JOINSTR_IT=1 and does not run in CI.
 */
@EnabledIfEnvironmentVariable(named = "JOINSTR_IT", matches = "1")
public class ThreePeerPoolIT {

    private static final String RELAY = "ws://127.0.0.1:7777";

    private final List<NostrListener> listeners = new ArrayList<>();

    @BeforeEach
    public void allowDirectConnections() {
        JoinstrTransport.setDirectForTesting(true);
    }

    @AfterEach
    public void cleanUp() {
        for(NostrListener listener : listeners) {
            try {
                listener.close();
            } catch(Exception e) {
                // nothing to close
            }
        }
        listeners.clear();
        JoinstrTransport.setDirectForTesting(false);
    }

    /** A peer subscribed to the pool key, collecting what it decrypts. */
    private record Peer(Identity own, List<String> received, Map<String, Long> times) {
    }

    private Peer join(Identity poolIdentity) {
        List<String> received = new CopyOnWriteArrayList<>();
        Map<String, Long> times = new ConcurrentHashMap<>();

        NostrListener listener = new NostrListener(poolIdentity, RELAY, null);
        listener.startListening((message, createdAt) -> {
            received.add(message);
            times.put(message, createdAt);
        });
        listeners.add(listener);

        return new Peer(poolIdentity, received, times);
    }

    private void waitFor(java.util.function.BooleanSupplier done, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while(System.currentTimeMillis() < deadline) {
            if(done.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        fail("timed out waiting for " + what);
    }

    @Test
    public void threePeersAllSeeEveryOutputRegistration() throws Exception {
        Identity poolIdentity = Identity.generateRandomIdentity();

        List<Peer> peers = List.of(join(poolIdentity), join(poolIdentity), join(poolIdentity));
        Thread.sleep(1000);

        List<String> addresses = List.of(
                "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
                "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
                "bc1q9d4ywgfnd8h43da5tpcxcn6ajv590cg6d3tg6axemvljvt2k76zs50tv4q");

        for(String address : addresses) {
            JoinstrMessage output = JoinstrMessage.of("output");
            output.setAddress(address);
            assertTrue(TestPoolPublisher.publish(poolIdentity, RELAY, output.toJson()),
                    "failed to publish an output");
            Thread.sleep(300);
        }

        for(Peer peer : peers) {
            waitFor(() -> peer.received().size() >= 3, "all three outputs at one peer");
        }

        for(Peer peer : peers) {
            List<String> seen = new ArrayList<>();
            for(String message : peer.received()) {
                seen.add(JoinstrMessage.fromJson(message).getAddress());
            }
            assertTrue(seen.containsAll(addresses), "a peer missed an output: " + seen);
        }
    }

    /**
     * The bug this covers: publishing used to disconnect the shared nostr client, so a peer lost
     * its own subscription the moment it registered and never saw anyone else's messages.
     */
    @Test
    public void aPeerStillReceivesAfterPublishingItsOwnMessages() throws Exception {
        Identity poolIdentity = Identity.generateRandomIdentity();

        Peer peer = join(poolIdentity);
        Thread.sleep(1000);

        JoinstrMessage mine = JoinstrMessage.of("output");
        mine.setAddress("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq");
        assertTrue(TestPoolPublisher.publish(poolIdentity, RELAY, mine.toJson()));

        waitFor(() -> peer.received().size() >= 1, "our own output to come back");

        // now somebody else publishes; a peer whose subscription died here would never see it
        JoinstrMessage theirs = JoinstrMessage.of("output");
        theirs.setAddress("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4");
        assertTrue(TestPoolPublisher.publish(poolIdentity, RELAY, theirs.toJson()));

        waitFor(() -> peer.received().size() >= 2,
                "a message published after our own, which needs the subscription to have survived");
    }

    @Test
    public void everyPeerOrdersTheOutputsIdentically() throws Exception {
        Identity poolIdentity = Identity.generateRandomIdentity();

        List<CoinjoinHandler> handlers = new ArrayList<>();
        List<Peer> peers = new ArrayList<>();
        for(int i = 0; i < 3; i++) {
            JoinstrPool pool = new JoinstrPool(RELAY, poolIdentity.getPublicKey().toString(),
                    "0.001", "3", String.valueOf(java.time.Instant.now().getEpochSecond() + 3600));
            handlers.add(new CoinjoinHandler(poolIdentity, pool, null, null, status -> {
            }));
            peers.add(join(poolIdentity));
        }
        Thread.sleep(1000);

        List<String> addresses = List.of(
                "bc1q9d4ywgfnd8h43da5tpcxcn6ajv590cg6d3tg6axemvljvt2k76zs50tv4q",
                "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
                "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4");

        for(String address : addresses) {
            JoinstrMessage output = JoinstrMessage.of("output");
            output.setAddress(address);
            assertTrue(TestPoolPublisher.publish(poolIdentity, RELAY, output.toJson()));
            Thread.sleep(1100);
        }

        for(Peer peer : peers) {
            waitFor(() -> peer.received().size() >= 3, "all outputs");
        }

        // feed each handler its peer's messages in the order that peer saw them, then compare
        List<List<String>> orders = new ArrayList<>();
        for(int i = 0; i < 3; i++) {
            Peer peer = peers.get(i);
            List<String> shuffled = new ArrayList<>(peer.received());
            Collections.shuffle(shuffled);
            for(String message : shuffled) {
                handlers.get(i).handleDecryptedMessage(message, peer.times().get(message));
            }
            orders.add(handlers.get(i).orderedOutputs());
        }

        assertEquals(orders.get(0), orders.get(1), "peers disagreed on the output order");
        assertEquals(orders.get(1), orders.get(2), "peers disagreed on the output order");
        assertEquals(3, orders.get(0).size());
    }
}
