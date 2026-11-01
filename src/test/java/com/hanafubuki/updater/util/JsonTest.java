package com.hanafubuki.updater.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class JsonTest {

    private static final JsonObject OBJ = JsonParser.parseString("""
            {"s": "text", "n": 12345678901, "f": 1.5, "b": true, "sb": "true", "nul": null,
             "o": {"x": 1}, "a": [1, "Paper", null, {"k": 1}], "ns": 7}
            """).getAsJsonObject();

    @Test
    void parseValidJson() throws IOException {
        assertTrue(Json.parse("{\"a\":1}", "test").isJsonObject());
        assertEquals(2, Json.parseArray("[1,2]", "test").size());
        assertEquals("b", Json.parseObject("{\"a\":\"b\"}", "test").get("a").getAsString());
    }

    @Test
    void malformedJsonNamesTheSource() {
        IOException e = assertThrows(IOException.class, () -> Json.parse("{\"a\":", "Modrinth versions of x"));
        assertTrue(e.getMessage().startsWith("Invalid JSON from Modrinth versions of x"), e.getMessage());
        assertTrue(e.getCause() != null);
    }

    @Test
    void wrongTopLevelTypeIsAnIOException() {
        IOException obj = assertThrows(IOException.class, () -> Json.parseObject("[1]", "api"));
        assertEquals("Expected a JSON object from api", obj.getMessage());
        IOException arr = assertThrows(IOException.class, () -> Json.parseArray("{}", "api"));
        assertEquals("Expected a JSON array from api", arr.getMessage());
        assertThrows(IOException.class, () -> Json.parseArray("\"text\"", "api"));
    }

    @Test
    void str() {
        assertEquals("text", Json.str(OBJ, "s"));
        assertEquals("12345678901", Json.str(OBJ, "n"));
        assertEquals("true", Json.str(OBJ, "b"));
        assertNull(Json.str(OBJ, "nul"));
        assertNull(Json.str(OBJ, "o"));
        assertNull(Json.str(OBJ, "a"));
        assertNull(Json.str(OBJ, "missing"));
        assertNull(Json.str(null, "s"));
    }

    @Test
    void bool() {
        assertTrue(Json.bool(OBJ, "b", false));
        // strings are not booleans
        assertFalse(Json.bool(OBJ, "sb", false));
        assertTrue(Json.bool(OBJ, "missing", true));
        assertFalse(Json.bool(OBJ, "nul", false));
        assertTrue(Json.bool(OBJ, "n", true));
        assertTrue(Json.bool(null, "b", true));
    }

    @Test
    void num() {
        assertEquals(12345678901L, Json.num(OBJ, "n", -1));
        assertEquals(7, Json.num(OBJ, "ns", -1));
        assertEquals(1, Json.num(OBJ, "f", -1));
        assertEquals(-1, Json.num(OBJ, "s", -1));
        assertEquals(-1, Json.num(OBJ, "b", -1));
        assertEquals(-1, Json.num(OBJ, "missing", -1));
        assertEquals(-1, Json.num(OBJ, "nul", -1));
        assertEquals(-2, Json.num(null, "n", -2));
    }

    @Test
    void obj() {
        assertEquals(1, Json.obj(OBJ, "o").get("x").getAsInt());
        assertNull(Json.obj(OBJ, "a"));
        assertNull(Json.obj(OBJ, "s"));
        assertNull(Json.obj(OBJ, "missing"));
        assertNull(Json.obj(null, "o"));
    }

    @Test
    void arr() {
        assertEquals(4, Json.arr(OBJ, "a").size());
        assertEquals(0, Json.arr(OBJ, "o").size());
        assertEquals(0, Json.arr(OBJ, "missing").size());
        assertEquals(0, Json.arr(null, "a").size());
    }

    @Test
    void containsIgnoreCase() {
        JsonArray a = Json.arr(OBJ, "a");
        assertTrue(Json.containsIgnoreCase(a, "paper"));
        assertTrue(Json.containsIgnoreCase(a, "PAPER"));
        assertTrue(Json.containsIgnoreCase(a, "1"));
        assertFalse(Json.containsIgnoreCase(a, "spigot"));
        assertFalse(Json.containsIgnoreCase(new JsonArray(), "paper"));
    }
}
