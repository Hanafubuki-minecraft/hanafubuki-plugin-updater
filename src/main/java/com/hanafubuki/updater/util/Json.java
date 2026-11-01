package com.hanafubuki.updater.util;

import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Null-safe helpers for reading API responses with Gson.
 */
public final class Json {

    private Json() {
    }

    /** Parses JSON text; malformed input becomes an IOException naming the source. */
    public static JsonElement parse(String text, String what) throws IOException {
        try {
            return JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw new IOException("Invalid JSON from " + what + ": " + e.getMessage(), e);
        }
    }

    public static JsonObject parseObject(String text, String what) throws IOException {
        JsonElement e = parse(text, what);
        if (!e.isJsonObject()) {
            throw new IOException("Expected a JSON object from " + what);
        }
        return e.getAsJsonObject();
    }

    public static JsonArray parseArray(String text, String what) throws IOException {
        JsonElement e = parse(text, what);
        if (!e.isJsonArray()) {
            throw new IOException("Expected a JSON array from " + what);
        }
        return e.getAsJsonArray();
    }

    /** String member, or null if missing, null or not a primitive. */
    public static String str(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null) {
            return def;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : def;
    }

    public static long num(JsonObject o, String key, long def) {
        if (o == null) {
            return def;
        }
        JsonElement e = o.get(key);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsLong();
        }
        return def;
    }

    /** Object member, or null. */
    public static JsonObject obj(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** Array member, or an empty array. */
    public static JsonArray arr(JsonObject o, String key) {
        if (o == null) {
            return new JsonArray();
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    /** True if the array contains the string, ignoring case. */
    public static boolean containsIgnoreCase(JsonArray array, String value) {
        for (JsonElement e : array) {
            if (e.isJsonPrimitive() && e.getAsString().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
