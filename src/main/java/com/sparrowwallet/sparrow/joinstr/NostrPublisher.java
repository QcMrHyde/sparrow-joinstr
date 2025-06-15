package com.sparrowwallet.sparrow.joinstr;

import nostr.api.NIP01;
import nostr.api.NIP04;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.id.Identity;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.wallet.NodeEntry;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.AppServices;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class NostrPublisher {

    private static final Identity SENDER = Identity.generateRandomIdentity();

    private static final Map<String, String> RELAYS = Map.of(
            "nos", "wss://nos.lol"
    );

    public static void main(String[] args) {
        String defaultDenomination = "100000";
        String defaultPeers = "5";
        GenericEvent event = publishCustomEvent(defaultDenomination, defaultPeers);
        if (event != null) {
            System.out.println("Event ID: " + event.getId());
        }
    }

    public static String getNewReceiveAddress(Storage storage, Wallet wallet) {
        WalletForm walletForm = new WalletForm(storage, wallet);
        EventManager.get().register(walletForm);
        NodeEntry freshEntry = walletForm.getFreshNodeEntry(KeyPurpose.RECEIVE, null);
        return freshEntry.getAddress().toString();
    }

    public static GenericEvent publishCustomEvent(String denomination, String peers) {
        Map<Wallet, Storage> openWallets = AppServices.get().getOpenWallets();
        if (openWallets.isEmpty()) {
            System.err.println("No wallet found. Please open a wallet in Sparrow first.");
            return null;
        }

        Map.Entry<Wallet, Storage> firstWallet = openWallets.entrySet().iterator().next();
        Wallet wallet = firstWallet.getKey();
        Storage storage = firstWallet.getValue();

        String bitcoinAddress;
        try {
            System.out.println("Public key: " + SENDER.getPublicKey().toString());
            System.out.println("Private key: " + SENDER.getPrivateKey().toString());

            Identity poolIdentity = Identity.generateRandomIdentity();
            bitcoinAddress = getNewReceiveAddress(storage, wallet);

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

            return event;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return null;
            }
        }
    }