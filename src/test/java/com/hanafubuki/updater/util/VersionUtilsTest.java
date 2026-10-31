package com.hanafubuki.updater.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VersionUtilsTest {

    @ParameterizedTest(name = "{0} > {1}")
    @CsvSource({
            // v prefix (GitHub tags) - the old code read "v2.0.0" as 0.0.0
            "v2.0.0, 1.5.0",
            "V1.2.4, 1.2.3",
            "release-1.3, 1.2.9",
            // more segments
            "1.2.3.4, 1.2.3",
            "1.10.0, 1.9.9",
            "2.0, 1.99.99",
            // pre-release order
            "2.0.0, 2.0.0-rc1",
            "2.0.0-rc1, 2.0.0-beta.5",
            "2.0.0-beta.2, 2.0.0-beta.1",
            "2.0.0-beta.1, 2.0.0-alpha.9",
            "2.0.0-alpha, 2.0.0-SNAPSHOT",
            "1.0.1-SNAPSHOT, 1.0.0",
            // build numbers
            "2.12.1-b1280, 2.12.1-b1279",
            "5.12.3-SNAPSHOT+1113, 5.12.3-SNAPSHOT+1112",
            "1.0.0-45, 1.0.0-44",
            "2.12.2-b1, 2.12.1-b9999",
            // platform words are ignored
            "v5.5.71-bukkit, 5.5.70",
    })
    void newer(String newer, String older) {
        assertTrue(VersionUtils.isNewer(newer, older), newer + " should be newer than " + older);
        assertFalse(VersionUtils.isNewer(older, newer), older + " should not be newer than " + newer);
    }

    @ParameterizedTest(name = "{0} == {1}")
    @CsvSource({
            "1.2, 1.2.0",
            "v1.2.3, 1.2.3",
            "v5.5.71-bukkit, 5.5.71",
            "1.2.3-paper, 1.2.3",
            "2.0.0-Final, 2.0.0",
    })
    void equal(String a, String b) {
        assertEquals(0, VersionUtils.compare(a, b));
        assertFalse(VersionUtils.isNewer(a, b));
        assertFalse(VersionUtils.isNewer(b, a));
    }

    @Test
    void blankAndNullAreNeverNewer() {
        assertFalse(VersionUtils.isNewer(null, "1.0"));
        assertFalse(VersionUtils.isNewer("1.0", null));
        assertFalse(VersionUtils.isNewer("", "1.0"));
        assertFalse(VersionUtils.isNewer("latest", "1.0"), "'latest' is not a version and must not trigger downloads");
    }

    @Test
    void comparatorSorts() {
        List<String> versions = new ArrayList<>(List.of("1.10", "v1.2", "1.2-beta", "1.9.1", "1.2.0.1"));
        versions.sort(VersionUtils.COMPARATOR);
        assertEquals(List.of("1.2-beta", "v1.2", "1.2.0.1", "1.9.1", "1.10"), versions);
    }

    @Test
    void inRange() {
        assertTrue(VersionUtils.inRange("1.5", "1.0", "2.0"));
        assertTrue(VersionUtils.inRange("1.0", "1.0", "2.0"));
        assertTrue(VersionUtils.inRange("2.0", "1.0", "2.0"));
        assertFalse(VersionUtils.inRange("0.9", "1.0", "2.0"));
        assertFalse(VersionUtils.inRange("2.0.1", "1.0", "2.0"));
        assertTrue(VersionUtils.inRange("99.0", null, "latest"));
        assertTrue(VersionUtils.inRange("0.1", "none", ""));
    }

    @Test
    void advisoryRanges() {
        assertTrue(VersionUtils.matchesRange("1.0.0", ">= 1.0.0, < 1.0.1"));
        assertFalse(VersionUtils.matchesRange("1.0.1", ">= 1.0.0, < 1.0.1"));
        assertTrue(VersionUtils.matchesRange("1.9", "< 2.0"));
        assertTrue(VersionUtils.matchesRange("2.0", "<= 2.0"));
        assertTrue(VersionUtils.matchesRange("2.0.0", "= 2.0.0"));
        assertTrue(VersionUtils.matchesRange("2.0.0", "2.0.0"));
        assertFalse(VersionUtils.matchesRange("2.0.1", "= 2.0.0"));
        assertTrue(VersionUtils.matchesRange("v3.1", "> 3.0"));
        assertThrows(IllegalArgumentException.class, () -> VersionUtils.matchesRange("1.0", ""));
        assertThrows(IllegalArgumentException.class, () -> VersionUtils.matchesRange("1.0", ">="));
    }

    @Test
    void normalize() {
        assertEquals("2.0.0", VersionUtils.normalize("v2.0.0"));
        assertEquals("1.3", VersionUtils.normalize("release-1.3"));
        assertEquals("abc", VersionUtils.normalize("abc"));
    }
}
