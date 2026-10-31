package com.hanafubuki.updater.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VersionUtils version comparison logic.
 */
class VersionUtilsTest {

    @ParameterizedTest
    @CsvSource({
        // Basic version comparison
        "1.0.1, 1.0.0, true",
        "1.1.0, 1.0.0, true",
        "2.0.0, 1.9.9, true",
        "1.0.0, 1.0.1, false",
        "1.0.0, 1.0.0, false",

        // Pre-release handling (release > pre-release)
        "1.21.9, 1.21.9-R0.1-SNAPSHOT, true",
        "1.21.9, 1.21.9-beta, true",
        "1.21.9, 1.21.9-rc1, true",
        "1.21.9, 1.21.9-alpha, true",

        // Pre-release type ordering: release > rc > beta > alpha > SNAPSHOT
        "1.21.9-rc1, 1.21.9-beta, true",
        "1.21.9-rc1, 1.21.9-alpha, true",
        "1.21.9-beta, 1.21.9-alpha, true",
        "1.21.9-beta, 1.21.9-SNAPSHOT, true",
        "1.21.9-alpha, 1.21.9-SNAPSHOT, true",

        // Paper-style versions
        "1.21.9-R0.2, 1.21.9-R0.1, true",
        "1.21.9-R0.1, 1.21.9-R0.1-SNAPSHOT, true",
        "1.21.9-R1.0, 1.21.9-R0.1, true",

        // Numeric suffix comparison
        "1.21.9-rc2, 1.21.9-rc1, true",
        "1.21.9-beta2, 1.21.9-beta1, true",
        "1.21.9-alpha3, 1.21.9-alpha2, true",

        // Special cases
        "latest, 1.0.0, true",
        "1.0.0, latest, false",

        // Edge cases - different lengths (1.0 == 1.0.0)
        "1.0, 1.0.0, false",
        "1.0.0.0, 1.0.0, false",
    })
    void testIsVersionNewer(String newVersion, String currentVersion, boolean expected) {
        assertEquals(expected, VersionUtils.isVersionNewer(newVersion, currentVersion),
            String.format("isVersionNewer('%s', '%s') should be %s", newVersion, currentVersion, expected));
    }

    @ParameterizedTest
    @CsvSource({
        "1.0.0, 1.0.0, true",
        "1.0.1, 1.0.0, true",
        "2.0.0, 1.0.0, true",
        "1.0.0, 1.0.1, false",
        "1.21.9, 1.21.9-R0.1, true",
        "1.21.9-R0.1, 1.21.9, false",
        "1.0.0, none, true",  // minVersion="none" means no minimum
        "1.0.0, NONE, true",
    })
    void testMeetsMinimumVersion(String pluginVersion, String minVersion, boolean expected) {
        assertEquals(expected, VersionUtils.meetsMinimumVersion(pluginVersion, minVersion),
            String.format("meetsMinimumVersion('%s', '%s') should be %s", pluginVersion, minVersion, expected));
    }

    @Test
    void testNullHandling() {
        assertFalse(VersionUtils.isVersionNewer(null, "1.0.0"));
        assertFalse(VersionUtils.isVersionNewer("1.0.0", null));
        assertFalse(VersionUtils.isVersionNewer(null, null));

        assertFalse(VersionUtils.meetsMinimumVersion(null, "1.0.0"));
        assertFalse(VersionUtils.meetsMinimumVersion("1.0.0", null));
    }

    @Test
    void testEmptyStrings() {
        // Empty strings are treated as version 0.0.0
        assertFalse(VersionUtils.isVersionNewer("", "1.0.0"));
        assertTrue(VersionUtils.isVersionNewer("1.0.0", ""));
    }

    @Test
    void testPaperStyleVersions() {
        // Paper versions like 1.21.9-R0.1 should be parsed correctly
        assertTrue(VersionUtils.isVersionNewer("1.21.9-R0.2", "1.21.9-R0.1"));
        assertTrue(VersionUtils.isVersionNewer("1.21.9-R1.0", "1.21.9-R0.9"));
    }
}