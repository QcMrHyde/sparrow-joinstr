package com.sparrowwallet.sparrow.joinstr;

import nostr.api.NIP01;
import nostr.api.NIP04;
import nostr.event.BaseTag;
import nostr.event.Kind;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.PubKeyTag;
import nostr.id.Identity;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.wallet.NodeEntry;
import com.sparrowwallet.sparrow.EventManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class NostrPublisher implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(NostrPublisher.class.getName());
    private static final Identity SENDER = Identity.generateRandomIdentity();

    private String poolPrivateKey = "";

    public String getPoolPrivateKey() {
        return poolPrivateKey;
    }

    private static final Map<String, String> RELAYS = Map.of(
            "nos", "wss://nos.lol");

    public Address getNewReceiveAddress(Storage storage, Wallet wallet) {
        WalletForm walletForm = new WalletForm(storage, wallet);
        EventManager.get().register(walletForm);
        NodeEntry freshEntry = walletForm.getFreshNodeEntry(KeyPurpose.RECEIVE, null);
        return freshEntry.getAddress();
    }

    public GenericEvent publishCustomEvent(String denomination, String peers, String bitcoinAddress) {

        if (bitcoinAddress.isEmpty()) {
            logger.warning("No Bitcoin Address found. Please open a wallet in Sparrow first.");
            return null;
        }

        Identity poolIdentity;

        try {
            logger.info("Public key: " + SENDER.getPublicKey().toString());

            poolIdentity = Identity.generateRandomIdentity();
            poolPrivateKey = poolIdentity.getPrivateKey().toString();

            long timeout = Instant.now().getEpochSecond() + 3600;

            List<BaseTag> tags = new ArrayList<>();
            String poolId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String content = String.format(
                    "{\n" +
                            "  \"type\": \"new_pool\",\n" +
                            "  \"id\": \"%s\",\n" +
                            "  \"public_key\": \"%s\",\n" +
                            "  \"denomination\": %s,\n" +
                            "  \"peers\": %s,\n" +
                            "  \"timeout\": %d,\n" +
                            "  \"relay\": \"%s\",\n" +
                            "  \"fee_rate\": 1,\n" +
                            "  \"transport\": \"tor\",\n" +
                            "  \"vpn_gateway\": null\n" +
                            "}",
                    poolId,
                    poolIdentity.getPublicKey().toString(),
                    denomination,
                    peers,
                    timeout,
                    RELAYS.values().iterator().next());

            NIP01 nip01 = new NIP01(SENDER);

            GenericEvent event = new GenericEvent(
                    SENDER.getPublicKey(),
                    Kind.CONJOIN_POOL.getValue(),
                    tags,
                    content);

            nip01.setEvent(event);
            nip01.sign();

            nip01.send(RELAYS);

            logger.info("Event ID: " + event.getId());
            logger.info("Event: " + event);

            return event;

        } catch (Exception e) {
            logger.severe("Error: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void close() throws Exception {
        // No resources to close
    }
}
