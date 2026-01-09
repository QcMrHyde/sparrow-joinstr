package com.sparrowwallet.sparrow.joinstr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;

/**
 * Utility class for serializing/deserializing ArrayList&lt;JoinstrPool&gt; directly.
 * This eliminates the need for JoinstrPoolStoreWrapper.
 */
public class JoinstrPoolSerializer {

    private static Gson gson;
    private static final Type POOL_LIST_TYPE = new TypeToken<ArrayList<JoinstrPool>>(){}.getType();

    /**
     * Creates a Gson instance configured with the FileTypeAdapter.
     *
     * @return A configured Gson instance
     */
    public static Gson createGson() {
        if (gson == null) {
            gson = new GsonBuilder()
                    .registerTypeAdapter(File.class, new FileTypeAdapter())
                    .setPrettyPrinting()
                    .create();
        }
        return gson;
    }

    /**
     * Serializes an ArrayList&lt;JoinstrPool&gt; to JSON.
     *
     * @param poolsList The list to serialize
     * @return JSON string representation
     */
    public static String toJson(ArrayList<JoinstrPool> poolsList) {
        return createGson().toJson(poolsList, POOL_LIST_TYPE);
    }

    /**
     * Deserializes JSON back to an ArrayList&lt;JoinstrPool&gt;.
     *
     * @param json The JSON string to deserialize
     * @return The deserialized ArrayList&lt;JoinstrPool&gt;
     */
    public static ArrayList<JoinstrPool> fromJson(String json) {
        return createGson().fromJson(json, POOL_LIST_TYPE);
    }
}