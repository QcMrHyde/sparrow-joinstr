package com.sparrowwallet.sparrow.joinstr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nostr.event.impl.GenericEvent;
import nostr.id.Identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class NostrPublisherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GenericEvent event(Identity poolIdentity) {
        return NostrPublisher.buildPoolEvent(poolIdentity, "0123456789abcdef", "regtest", "0.001", "3",
                1750000000L, "wss://nos.lol");
    }

    @Test
    public void announcementIsAuthoredByThePoolKeyItAdvertises() throws Exception {
        Identity poolIdentity = Identity.generateRandomIdentity();
        GenericEvent event = event(poolIdentity);

        JsonNode content = MAPPER.readTree(event.getContent());

        // other joinstr clients drop a pool event whose author is not the pool key in its content
        assertEquals(event.getPubKey().toString(), content.get("public_key").asText());
        assertEquals(poolIdentity.getPublicKey().toString(), content.get("public_key").asText());
    }

    @Test
    public void announcementUsesTheCoinjoinPoolKind() {
        assertEquals(2022, event(Identity.generateRandomIdentity()).getKind());
    }

    @Test
    public void eachPoolGetsItsOwnAnnouncerKey() throws Exception {
        // a shared announcer key would publicly link every pool created in one session
        JsonNode first = MAPPER.readTree(event(Identity.generateRandomIdentity()).getContent());
        JsonNode second = MAPPER.readTree(event(Identity.generateRandomIdentity()).getContent());

        assertNotEquals(first.get("public_key").asText(), second.get("public_key").asText());
    }

    @Test
    public void announcementCarriesTheFieldsOtherClientsRequire() throws Exception {
        JsonNode content = MAPPER.readTree(event(Identity.generateRandomIdentity()).getContent());

        for(String field : new String[] {"id", "public_key", "denomination", "peers", "timeout", "relay"}) {
            assertTrue(content.has(field), "missing required field: " + field);
        }
        assertEquals("0123456789abcdef", content.get("id").asText());
        assertEquals("regtest", content.get("network").asText());
        assertEquals(1750000000L, content.get("timeout").asLong());
        assertEquals("wss://nos.lol", content.get("relay").asText());
        assertEquals("wss://nos.lol", content.get("relays").get(0).asText());
    }
}
