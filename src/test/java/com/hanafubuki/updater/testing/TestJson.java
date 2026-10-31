package com.hanafubuki.updater.testing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Builds JSON for fake API answers without hand-written escaping.
 *
 * <pre>
 * String body = TestJson.obj("name", "5.12.2", "channel", TestJson.obj("name", "Release")).toString();
 * </pre>
 */
public final class TestJson {

    private TestJson() {
    }

    /** An object from key/value pairs. Values: String, Number, Boolean, JsonElement or null. */
    public static JsonObject obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("obj() needs key/value pairs");
        }
        JsonObject o = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            o.add(String.valueOf(keyValues[i]), toJson(keyValues[i + 1]));
        }
        return o;
    }

    /** An array of values. Values: String, Number, Boolean, JsonElement or null. */
    public static JsonArray arr(Object... values) {
        JsonArray a = new JsonArray();
        for (Object v : values) {
            a.add(toJson(v));
        }
        return a;
    }

    private static JsonElement toJson(Object v) {
        if (v == null) {
            return JsonNull.INSTANCE;
        }
        if (v instanceof JsonElement e) {
            return e;
        }
        if (v instanceof String s) {
            return new JsonPrimitive(s);
        }
        if (v instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (v instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + v.getClass());
    }

    /** Lowercase hex digest of a text, for fake hashes that are valid digests. */
    public static String digest(String algorithm, String text) {
        return digest(algorithm, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Lowercase hex digest of bytes; algorithm is a JCA name like "SHA-256". */
    public static String digest(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
