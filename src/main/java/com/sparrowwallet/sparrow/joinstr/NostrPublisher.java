package com.sparrowwallet.sparrow.joinstr;

import nostr.api.NIP01;
import nostr.api.NIP04;
import nostr.event.BaseTag;
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
import com.sparrowwallet.sparrow.AppServices;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class NostrPublisher {

    private static final Identity SENDER = Identity.generateRandomIdentity();

    private static final Map<String, String> RELAYS = Map.of(
            "nos", "wss://nos.lol"
    );

    public static Address getNewReceiveAddress(Storage storage, Wallet wallet) {
        WalletForm walletForm = new WalletForm(storage, wallet);
        EventManager.get().register(walletForm);
        NodeEntry freshEntry = walletForm.getFreshNodeEntry(KeyPurpose.RECEIVE, null);
        return freshEntry.getAddress();
    }

    public static GenericEvent publishCustomEvent(String denomination, String peers, String bitcoinAddress) {
        try {
            Map<Wallet, Storage> openWallets = AppServices.get().getOpenWallets();
            if (bitcoinAddress.isEmpty()) {
                System.err.println("No Bitcoin Address found. Please open a wallet in Sparrow first.");
                return null;
            }

            Map.Entry<Wallet, Storage> firstWallet = openWallets.entrySet().iterator().next();
            Wallet wallet = firstWallet.getKey();
            Storage storage = firstWallet.getValue();

            System.out.println("Public key: " + SENDER.getPublicKey().toString());
            System.out.println("Private key: " + SENDER.getPrivateKey().toString());

            Identity poolIdentity = Identity.generateRandomIdentity();

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
                    RELAYS.values().iterator().next()
            );

            NIP01 nip01 = new NIP01(SENDER);

            GenericEvent event = new GenericEvent(
                    SENDER.getPublicKey(),
                    2022,
                    tags,
                    content
            );

            nip01.setEvent(event);
            nip01.sign();

            nip01.send(RELAYS);

            if (event != null) {
                System.out.println("Event ID: " + event.getId());
                System.out.println("Event: " + event.toString());
            }

            String addressContent = String.format(
            "{\"type\":\"output\",\"address\":\"%s\"}",
            bitcoinAddress
            );

            NIP04 nip04 = new NIP04(poolIdentity, poolIdentity.getPublicKey());
            String encryptedContent = nip04.encrypt(poolIdentity, addressContent, poolIdentity.getPublicKey());

            tags.add(new PubKeyTag(poolIdentity.getPublicKey()));

            GenericEvent encrypted_event = new GenericEvent(
            poolIdentity.getPublicKey(),
            4,
            tags,
            encryptedContent
            );

            nip04.setEvent(encrypted_event);
            nip04.sign();
            nip04.send(RELAYS);

            if (encrypted_event != null) {
                System.out.println("Event ID: " + encrypted_event.getId());
                System.out.println("Event: " + encrypted_event.toString());
            }

            Map<String, String> poolCredentials = new HashMap<>();
            poolCredentials.put("id", poolId);
            poolCredentials.put("public_key", poolIdentity.getPublicKey().toString());
            poolCredentials.put("denomination", denomination);
            poolCredentials.put("peers", peers);
            poolCredentials.put("timeout", String.valueOf(timeout));
            poolCredentials.put("relay", RELAYS.values().iterator().next());
            poolCredentials.put("private_key", poolIdentity.getPrivateKey().toString());
            poolCredentials.put("fee_rate", "1");

            NewPoolController.shareCredentials(poolIdentity, RELAYS.toString(), poolCredentials);

            return event;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return null;
            }
        }
    }