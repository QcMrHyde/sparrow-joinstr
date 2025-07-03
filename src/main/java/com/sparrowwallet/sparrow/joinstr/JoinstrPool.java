package com.sparrowwallet.sparrow.joinstr;

import javafx.beans.property.SimpleStringProperty;

public class JoinstrPool {

    private final SimpleStringProperty relay;
    private final SimpleStringProperty pubkey;
    private final SimpleStringProperty denomination;
    private final SimpleStringProperty peers;
    private final SimpleStringProperty timeout;

    public String getRelay() { return relay.get(); }
    public String getPubkey() { return pubkey.get(); }
    public String getDenomination() { return denomination.get(); }
    public String getPeers() { return peers.get(); }
    public String getTimeout() { return timeout.get(); }

    public JoinstrEvent poolEvent;

    public JoinstrPool(JoinstrEvent poolEvent) {
        this.poolEvent = poolEvent;

        this.relay = new SimpleStringProperty(poolEvent.relay);
        this.pubkey = new SimpleStringProperty(poolEvent.public_key);
        this.denomination = new SimpleStringProperty(poolEvent.denomination);
        this.peers = new SimpleStringProperty(poolEvent.peers);
        this.timeout = new SimpleStringProperty(poolEvent.timeout);

    }

    public JoinstrPool(String relay, String pubkey, String denomination,
                       String peers, String timeout) {
        this.relay = new SimpleStringProperty(relay);
        this.pubkey = new SimpleStringProperty(pubkey);
        this.denomination = new SimpleStringProperty(denomination);
        this.peers = new SimpleStringProperty(peers);
        this.timeout = new SimpleStringProperty(timeout);
    }

}
