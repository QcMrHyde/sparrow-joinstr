package com.sparrowwallet.sparrow.joinstr;

import nostr.api.NIP04;
import nostr.client.Client;
import nostr.context.impl.DefaultRequestContext;
import nostr.event.BaseTag;
import nostr.event.Kind;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.PubKeyTag;
import nostr.id.Identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Publishes an encrypted message to a pool key, the way a peer does. */
final class TestPoolPublisher {

    private TestPoolPublisher() {
    }

    static boolean publish(Identity poolIdentity, String relay, String content) {
        try {
            List<BaseTag> tags = new ArrayList<>();
            tags.add(new PubKeyTag(poolIdentity.getPublicKey()));

            NIP04 nip04 = new NIP04(poolIdentity, poolIdentity.getPublicKey());
            String encrypted = nip04.encrypt(poolIdentity, content, poolIdentity.getPublicKey());

            GenericEvent event = new GenericEvent(poolIdentity.getPublicKey(),
                    Kind.ENCRYPTED_DIRECT_MESSAGE.getValue(), tags, encrypted);
            nip04.setEvent(event);
            nip04.sign();

            // the production publish path, so the test covers waiting for the relay's OK
            return JoinstrPublisher.publish(poolIdentity, relay, event);
        } catch (Exception e) {
            return false;
        }
    }
}
