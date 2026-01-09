package com.sparrowwallet.sparrow.joinstr;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.File;
import java.io.IOException;

/**
 * Custom Gson TypeAdapter for java.io.File.
 *
 * This adapter serializes File objects as their path strings and deserializes
 * path strings back to File objects. This is necessary because Gson cannot
 * access the private 'path' field in java.io.File via reflection in newer
 * Java versions (9+) due to module access restrictions.
 */
public class FileTypeAdapter extends TypeAdapter<File> {

    @Override
    public void write(JsonWriter out, File file) throws IOException {
        if (file == null) {
            out.nullValue();
        } else {
            out.value(file.getPath());
        }
    }

    @Override
    public File read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        String path = in.nextString();
        return new File(path);
    }
}