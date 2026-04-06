package com.sparrowwallet.sparrow.joinstr;

import java.util.Map;

public class JoinstrMessage {
    private String type;
    private String address;
    private String psbt;
    private String id;
    private String private_key;
    private Long fee_rate;

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

    public Long getFeeRate() {
        return fee_rate;
    }

    public void setFeeRate(Long fee_rate) {
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
