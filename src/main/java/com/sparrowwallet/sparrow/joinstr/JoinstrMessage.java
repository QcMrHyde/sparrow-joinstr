package com.sparrowwallet.sparrow.joinstr;

import java.util.Map;

public class JoinstrMessage {
    /** Every payload in NIP.md carries this. */
    public static final String VERSION = "1";

    private String version;
    private String type;
    private String address;
    private String psbt;
    private String id;
    private String private_key;
    private Double fee_rate;
    private String public_key;
    private Double denomination;
    private Integer peers;
    private Long timeout;
    private String relay;
    private String reason;
    private com.google.gson.JsonObject autct;
    private String autct_proof;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    /** A message this client sends, stamped with the protocol version. */
    public static JoinstrMessage of(String type) {
        JoinstrMessage message = new JoinstrMessage();
        message.setVersion(VERSION);
        message.setType(type);
        return message;
    }

    /** Whether a decrypted payload is a request to join a pool. */
    public static boolean isJoinRequest(String decryptedContent) {
        try {
            JoinstrMessage message = fromJson(decryptedContent);
            return message != null && "join_pool".equals(message.getType());
        } catch (Exception e) {
            return false;
        }
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPsbt() {
        return psbt;
    }

    public void setPsbt(String psbt) {
        this.psbt = psbt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPrivateKey() {
        return private_key;
    }

    public void setPrivateKey(String private_key) {
        this.private_key = private_key;
    }

    public String getPublicKey() {
        return public_key;
    }

    public void setPublicKey(String public_key) {
        this.public_key = public_key;
    }

    public Double getDenomination() {
        return denomination;
    }

    public void setDenomination(Double denomination) {
        this.denomination = denomination;
    }

    public Integer getPeers() {
        return peers;
    }

    public void setPeers(Integer peers) {
        this.peers = peers;
    }

    public Long getTimeout() {
        return timeout;
    }

    public void setTimeout(Long timeout) {
        this.timeout = timeout;
    }

    public String getRelay() {
        return relay;
    }

    public void setRelay(String relay) {
        this.relay = relay;
    }

    /** The aut-ct keyset named in these credentials, or null. */
    public String getAutctKeyset() {
        if (autct == null || !autct.has("keyset") || autct.get("keyset").isJsonNull()) {
            return null;
        }
        return autct.get("keyset").getAsString();
    }

    public void setAutctKeyset(String keyset) {
        com.google.gson.JsonObject object = new com.google.gson.JsonObject();
        object.addProperty("keyset", keyset);
        this.autct = object;
    }

    public String getAutctProof() {
        return autct_proof;
    }

    public void setAutctProof(String autct_proof) {
        this.autct_proof = autct_proof;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Double getFeeRate() {
        return fee_rate;
    }

    public void setFeeRate(Double fee_rate) {
        this.fee_rate = fee_rate;
    }

    public String toJson() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        return gson.toJson(this);
    }

    public static JoinstrMessage fromJson(String json) {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        return gson.fromJson(json, JoinstrMessage.class);
    }
}
