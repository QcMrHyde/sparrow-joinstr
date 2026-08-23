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
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.wallet.NodeEntry;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.net.TorUtils;
import nostr.client.Client;
import nostr.context.impl.DefaultRequestContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class NostrPublisher implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(NostrPublisher.class.getName());

    private String poolPrivateKey = "";

    public String getPoolPrivateKey() {
        return poolPrivateKey;
    }

    private static Map<String, String> relays() {
        return Map.of("default", JoinstrRelay.relayOrDefault(Config.get().getNostrRelay()));
    }

    public Address getNewReceiveAddress(Storage storage, Wallet wallet) {
        WalletForm walletForm = new WalletForm(storage, wallet);
        EventManager.get().register(walletForm);
        NodeEntry freshEntry = walletForm.getFreshNodeEntry(KeyPurpose.RECEIVE, null);
        return freshEntry.getAddress();
    }

    public GenericEvent publishCustomEvent(String denomination, String peers, String bitcoinAddress,
            double feeRate, long timeoutSeconds) {

        if (bitcoinAddress.isEmpty()) {
            logger.warning("No Bitcoin Address found. Please open a wallet in Sparrow first.");
            return null;
        }

        Identity poolIdentity;
        try {
            if (!JoinstrTransport.newCircuit()) {
                AppServices.showErrorDialog("Tor Not Running", JoinstrTransport.NOT_READY);
                return null;
            }

            poolIdentity = Identity.generateRandomIdentity();
            poolPrivateKey = poolIdentity.getPrivateKey().toString();

            long timeout = Instant.now().getEpochSecond() + timeoutSeconds;

            String poolId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Map<String, String> relays = relays();
            String relayUrl = relays.values().iterator().next();
            String network = com.sparrowwallet.drongo.Network.get().getName();

            NIP01 nip01 = new NIP01(poolIdentity);
            Client publisher = new Client();

            GenericEvent event = buildPoolEvent(poolIdentity, poolId, network, denomination, peers, timeout,
                    relayUrl, feeRate);

            nip01.setEvent(event);
            nip01.sign();

            {
                DefaultRequestContext context = new DefaultRequestContext();
                context.setPrivateKey(poolIdentity.getPrivateKey().getRawData());
                context.setRelays(relays);
                context.setProxy(JoinstrTransport.proxy());
                publisher.connect(context);
            }

            nip01.send(relays);

            try {
                publisher.disconnect();
            } catch (Exception e) {
                logger.fine("Error closing the announcement connection: " + e.getMessage());
            }

            logger.info("Event ID: " + event.getId());
            logger.info("Event: " + event);

            return event;

        } catch (Exception e) {
            logger.severe("Error: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Build the kind 2022 announcement. The event is authored by the pool key it advertises, which
     * is what other joinstr clients check before accepting the pool.
     */
    static GenericEvent buildPoolEvent(Identity poolIdentity, String poolId, String network, String denomination,
            String peers, long timeout, String relayUrl, double feeRate) {

        String content = String.format(
                "{\n" +
                        "  \"versions\": [\"1\"],\n" +
                        "  \"type\": \"new_pool\",\n" +
                        "  \"id\": \"%s\",\n" +
                        "  \"network\": \"%s\",\n" +
                        "  \"public_key\": \"%s\",\n" +
                        "  \"denomination\": %s,\n" +
                        "  \"peers\": %s,\n" +
                        "  \"timeout\": %d,\n" +
                        "  \"relays\": [\"%s\"],\n" +
                        "  \"relay\": \"%s\",\n" +
                        "  \"fee_rate\": %s,\n" +
                        "  \"transport\": { \"tor\": { \"enable\": true }, \"vpn\": { \"enable\": false, \"vpn_gateway\": null } }\n" +
                        "}",
                poolId,
                network,
                poolIdentity.getPublicKey().toString(),
                denomination,
                peers,
                timeout,
                relayUrl,
                relayUrl,
                CoinjoinMath.formatFeeRate(feeRate));

        List<BaseTag> tags = new ArrayList<>();

        return new GenericEvent(
                poolIdentity.getPublicKey(),
                Kind.CONJOIN_POOL.getValue(),
                tags,
                content);
    }

    @Override
    public void close() throws Exception {
        // No resources to close
    }
}
