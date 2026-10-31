package com.hanafubuki.updater.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class UpdaterSettingsTest {

    private static UpdaterSettings settings(String yaml) {
        SettingsParser.Result r = SettingsParser.parse(new Yaml().load(yaml), name -> null);
        assertEquals(List.of(), r.problems());
        return r.settings();
    }

    @Test
    void downloadTimeoutPerSource() {
        UpdaterSettings s = settings("""
                modrinth: {download_timeout: 11}
                github: {download_timeout: 12}
                hangar: {download_timeout: 13}
                spigotmc: {download_timeout: 14}
                custom: {download_timeout: 15}
                """);
        assertEquals(Duration.ofSeconds(11), s.downloadTimeout("modrinth"));
        assertEquals(Duration.ofSeconds(12), s.downloadTimeout("github"));
        assertEquals(Duration.ofSeconds(13), s.downloadTimeout("hangar"));
        assertEquals(Duration.ofSeconds(14), s.downloadTimeout("spigotmc"));
        assertEquals(Duration.ofSeconds(15), s.downloadTimeout("custom"));
        // local files have no source section
        assertEquals(Duration.ofSeconds(60), s.downloadTimeout("local"));
    }

    @Test
    void pluginNamesKeepConfigOrderAndAreImmutable() {
        UpdaterSettings s = settings("""
                plugins:
                  Zeta: {source: modrinth}
                  Alpha: {source: local}
                  Mid: {source: hangar}
                """);
        assertEquals(List.of("Zeta", "Alpha", "Mid"), s.pluginNames());
        assertThrows(UnsupportedOperationException.class, () -> s.pluginNames().add("x"));
    }

    @Test
    void modesAreCaseInsensitive() {
        UpdaterSettings.Modes modes = new UpdaterSettings.Modes(true, Set.of("LuckPerms"), Set.of("ViaVersion"));
        assertTrue(modes.isIncluded("luckperms"));
        assertTrue(modes.isIncluded("LUCKPERMS"));
        assertFalse(modes.isIncluded("Essentials"));
        assertTrue(modes.isExcluded("viaversion"));
        assertFalse(modes.isExcluded("LuckPerms"));
        UpdaterSettings.Modes empty = new UpdaterSettings.Modes(false, Set.of(), Set.of());
        assertFalse(empty.isIncluded("x"));
        assertFalse(empty.isExcluded("x"));
    }

    @Test
    void recordsCompareByValue() {
        assertEquals(new UpdaterSettings.Webhook(true, "https://x", Map.of("a", "b"), Set.of()),
                new UpdaterSettings.Webhook(true, "https://x", Map.of("a", "b"), Set.of()));
        assertEquals(new UpdaterSettings.Health(true, "127.0.0.1", 1), new UpdaterSettings.Health(true, "127.0.0.1", 1));
    }
}
