package com.sparrowwallet.sparrow.joinstr;

import com.google.gson.Gson;

public class JoinstrEvent {

    public String type;
    public String id;
    public String public_key;
    public String denomination;
    public String peers;
    public String timeout;
    public String relay;
    public String fee_rate;
    public String transport;

    public JoinstrEvent() { }

    public static JoinstrEvent fromJson(String eventContent) {
        Gson gson = new Gson();
        return gson.fromJson(eventContent, JoinstrEvent.class);
    }

}
