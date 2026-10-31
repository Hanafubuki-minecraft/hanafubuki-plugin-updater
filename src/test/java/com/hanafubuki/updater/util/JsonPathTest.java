package com.hanafubuki.updater.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

class JsonPathTest {

    private static final JsonElement DOC = JsonParser.parseString("""
            {
              "tag_name": "v1.2.3",
              "count": 42,
              "draft": false,
              "nothing": null,
              "with.dot": "dotted",
              "with space": "spaced",
              "nested": {"inner": {"value": "deep"}},
              "assets": [
                {"name": "first.jar", "browser_download_url": "https://example.com/first.jar"},
                {"name": "second.jar", "browser_download_url": "https://example.com/second.jar"},
                {"name": "last.jar", "browser_download_url": "https://example.com/last.jar"}
              ],
              "matrix": [[1, 2], [3, 4]]
            }
            """);

    private static Optional<String> read(String path) {
        return JsonPath.compile(path).readString(DOC);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "$.tag_name | v1.2.3",
            "tag_name | v1.2.3",
            ".tag_name | v1.2.3",
            "$.count | 42",
            "$.draft | false",
            "$.nested.inner.value | deep",
            "$['nested']['inner'][\"value\"] | deep",
            "$['with.dot'] | dotted",
            "$['with space'] | spaced",
            "$.assets[0].name | first.jar",
            "$.assets[1].browser_download_url | https://example.com/second.jar",
            "$.assets[-1].name | last.jar",
            "$.assets[-3].name | first.jar",
            "$.assets[ 2 ].name | last.jar",
            "$.matrix[1][0] | 3",
            "$.matrix[-1][-1] | 4",
    })
    void readsValues(String path, String expected) {
        assertEquals(Optional.of(expected), read(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "$.missing",
            "$.nested.missing.value",
            "$.nothing",
            "$.nothing.deeper",
            "$.assets[3].name",
            "$.assets[-4].name",
            // wrong types along the way
            "$.tag_name.length",
            "$.assets.name",
            "$.nested[0]",
            "$.count[0]",
    })
    void missingOrWrongTypeIsEmpty(String path) {
        assertEquals(Optional.empty(), JsonPath.compile(path).read(DOC));
        assertEquals(Optional.empty(), read(path));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertEquals(Optional.of("v1.2.3"), read("  $.tag_name  "));
    }

    @Test
    void rootPathReturnsDocument() {
        assertEquals(Optional.of(DOC), JsonPath.compile("$").read(DOC));
    }

    @Test
    void readStringIgnoresObjectsAndArrays() {
        JsonPath nested = JsonPath.compile("$.nested");
        assertTrue(nested.read(DOC).orElseThrow().isJsonObject());
        assertEquals(Optional.empty(), nested.readString(DOC));
        assertEquals(Optional.empty(), JsonPath.compile("$.assets").readString(DOC));
    }

    @Test
    void worksOnArrayRoot() {
        JsonElement arr = JsonParser.parseString("[{\"v\":\"a\"},{\"v\":\"b\"}]");
        assertEquals(Optional.of("b"), JsonPath.compile("$[1].v").readString(arr));
        assertEquals(Optional.of("a"), JsonPath.compile("[0].v").readString(arr));
    }

    @Test
    void nullRootIsEmpty() {
        assertEquals(Optional.empty(), JsonPath.compile("$.a").read(null));
    }

    @Test
    void toStringIsOriginalExpression() {
        assertEquals("$.assets[0].name", JsonPath.compile("$.assets[0].name").toString());
    }

    @Test
    void emptyExpressionIsRejected() {
        assertEquals("JSON path is empty", assertThrows(IllegalArgumentException.class, () -> JsonPath.compile(null)).getMessage());
        assertEquals("JSON path is empty", assertThrows(IllegalArgumentException.class, () -> JsonPath.compile("  ")).getMessage());
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "$.assets[0 | Missing ']'",
            "$.assets[name] | Unsupported selector [name]",
            "$.assets[*] | Unsupported selector [*]",
            "$.assets[] | Unsupported selector []",
            "$..name | Empty field name at position 2",
            "$.name. | Empty field name",
            "$name | Unexpected 'n' at position 1",
            "$.a[0]b | Unexpected 'b'",
    })
    void syntaxErrorsHaveReadableMessages(String expression, String messagePart) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> JsonPath.compile(expression));
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
        assertTrue(e.getMessage().contains(expression), "message names the expression: " + e.getMessage());
    }
}
