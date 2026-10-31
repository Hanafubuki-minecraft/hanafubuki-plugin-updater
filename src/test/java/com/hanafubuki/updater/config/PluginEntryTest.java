package com.hanafubuki.updater.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PluginEntryTest {

    /** Entry built from YAML text, the way config.yml provides it. */
    private static PluginEntry entry(String yaml) {
        Map<String, Object> options = new Yaml().load(yaml);
        return new PluginEntry("Test", " GitHub ", options);
    }

    @Test
    void sourceIsNormalised() {
        assertEquals("github", entry("a: b").source());
        assertNull(new PluginEntry("X", null, null).source());
    }

    @Test
    void optionsAreAnImmutableCopy() {
        Map<String, Object> options = new HashMap<>();
        options.put("repo", "a/b");
        PluginEntry e = new PluginEntry("X", "github", options);
        options.put("repo", "changed");
        assertEquals("a/b", e.string("repo"));
        assertThrows(UnsupportedOperationException.class, () -> e.options().put("x", 1));
        assertEquals(Map.of(), new PluginEntry("X", "local", null).options());
    }

    @Test
    void stringOption() {
        PluginEntry e = entry("""
                repo: "  owner/repo  "
                blank: "   "
                number: 42
                nothing: null
                """);
        assertEquals("owner/repo", e.string("repo"));
        assertNull(e.string("blank"));
        assertEquals("42", e.string("number"));
        assertNull(e.string("nothing"));
        assertNull(e.string("missing"));
    }

    @Test
    void boolOption() {
        PluginEntry e = entry("""
                yes_bool: true
                no_bool: false
                yes_text: "TRUE"
                no_text: "False"
                other_text: "maybe"
                number: 1
                """);
        assertTrue(e.bool("yes_bool", false));
        assertFalse(e.bool("no_bool", true));
        assertTrue(e.bool("yes_text", false));
        assertFalse(e.bool("no_text", true));
        assertTrue(e.bool("other_text", true));
        assertFalse(e.bool("other_text", false));
        assertTrue(e.bool("number", true));
        assertFalse(e.bool("missing", false));
        assertTrue(e.bool("missing", true));
    }

    @Test
    void listOption() {
        PluginEntry e = entry("""
                loaders: [paper, " purpur ", "", null]
                single: paper
                blank: "  "
                """);
        assertEquals(List.of("paper", "purpur"), e.list("loaders"));
        assertEquals(List.of("paper"), e.list("single"));
        assertEquals(List.of(), e.list("blank"));
        assertNull(e.list("missing"));
    }

    @Test
    void mapOption() {
        PluginEntry e = entry("""
                headers:
                  Authorization: Bearer x
                  X-Number: 5
                  X-Null: null
                not_a_map: text
                """);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Authorization", "Bearer x");
        expected.put("X-Number", "5");
        assertEquals(expected, e.map("headers"));
        assertEquals(Map.of(), e.map("not_a_map"));
        assertEquals(Map.of(), e.map("missing"));
    }

    @Test
    void versionLimits() {
        PluginEntry e = entry("min_version: 1.2\nmax_version: \"2.0.0\"\n");
        assertEquals("1.2", e.minVersion());
        assertEquals("2.0.0", e.maxVersion());
        PluginEntry none = entry("a: b");
        assertNull(none.minVersion());
        assertNull(none.maxVersion());
    }
}
