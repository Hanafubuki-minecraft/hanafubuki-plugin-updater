package com.hanafubuki.updater.util;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for version comparison and handling.
 * Implements semantic version comparison following SemVer-like rules.
 */
public class VersionUtils {

    private static final Pattern VERSION_PATTERN = Pattern.compile(
        "^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?" +           // major.minor.patch
        "(?:-([0-9A-Za-z.-]+))?" +                        // pre-release
        "(?:\\+([0-9A-Za-z.-]+))?$"                       // build metadata
    );

    private static final List<String> PRE_RELEASE_ORDER = List.of(
        "SNAPSHOT", "alpha", "beta", "rc", "final", "release"
    );

    /**
     * Comparator for version strings using semantic version ordering.
     * Null versions are treated as older than any non-null version.
     */
    public static final Comparator<String> VERSION_COMPARATOR = (v1, v2) -> {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        return isVersionNewer(v1, v2) ? 1 : (isVersionNewer(v2, v1) ? -1 : 0);
    };

    /**
     * Check if a new version is newer than the current version.
     * Handles semantic versions with pre-release identifiers like:
     * 1.21.9-R0.1-SNAPSHOT, 1.21.9-beta, 1.21.9-rc1, etc.
     *
     * @param newVersion The new version string to compare
     * @param currentVersion The current version string to compare against
     * @return true if newVersion is newer, false otherwise
     */
    public static boolean isVersionNewer(String newVersion, String currentVersion) {
        if (newVersion == null || currentVersion == null) {
            return false;
        }

        // Handle special cases
        if ("latest".equalsIgnoreCase(newVersion)) {
            return true;
        }
        if ("latest".equalsIgnoreCase(currentVersion)) {
            return false;
        }

        Version newVer = parseVersion(newVersion);
        Version currentVer = parseVersion(currentVersion);

        return compareVersions(newVer, currentVer) > 0;
    }

    /**
     * Check if a plugin version meets the minimum required version.
     *
     * @param pluginVersion The version of the plugin to check
     * @param minVersion The minimum required version
     * @return true if plugin version meets minimum, false otherwise
     */
    public static boolean meetsMinimumVersion(String pluginVersion, String minVersion) {
        if (pluginVersion == null || minVersion == null) {
            return false;
        }

        // Handle special case where we don't want to check versions
        if ("none".equalsIgnoreCase(minVersion)) {
            return true;
        }

        // pluginVersion >= minVersion
        return !isVersionNewer(minVersion, pluginVersion);
    }

    /**
     * Parse a version string into a Version object.
     *
     * @param versionString The version string to parse
     * @return Parsed Version object
     */
    private static Version parseVersion(String versionString) {
        Matcher matcher = VERSION_PATTERN.matcher(versionString);
        if (!matcher.matches()) {
            // Fallback for non-standard versions - try to extract numbers
            return parseFallback(versionString);
        }

        int major = parseIntSafe(matcher.group(1), 0);
        int minor = parseIntSafe(matcher.group(2), 0);
        int patch = parseIntSafe(matcher.group(3), 0);
        String preRelease = matcher.group(4);
        String build = matcher.group(5);

        return new Version(major, minor, patch, preRelease, build);
    }

    /**
     * Fallback parsing for non-standard version strings.
     */
    private static Version parseFallback(String versionString) {
        // Try to split by common separators
        String[] parts = versionString.split("[.-]");
        int major = 0, minor = 0, patch = 0;
        String preRelease = null;

        for (int i = 0; i < parts.length && i < 3; i++) {
            int value = parseIntSafe(parts[i], 0);
            if (i == 0) major = value;
            else if (i == 1) minor = value;
            else if (i == 2) patch = value;
        }

        // Check if last part looks like a pre-release
        if (parts.length > 3) {
            preRelease = String.join("-", Arrays.copyOfRange(parts, 3, parts.length));
        }

        return new Version(major, minor, patch, preRelease, null);
    }

    /**
     * Compare two Version objects.
     * Returns positive if v1 > v2, negative if v1 < v2, 0 if equal.
     */
    private static int compareVersions(Version v1, Version v2) {
        // Compare major, minor, patch
        if (v1.major != v2.major) return Integer.compare(v1.major, v2.major);
        if (v1.minor != v2.minor) return Integer.compare(v1.minor, v2.minor);
        if (v1.patch != v2.patch) return Integer.compare(v1.patch, v2.patch);

        // Numeric parts equal - compare pre-release
        return comparePreRelease(v1.preRelease, v2.preRelease);
    }

    /**
     * Compare pre-release identifiers.
     * Release (null/empty) > rc > beta > alpha > SNAPSHOT
     * For same type, compare numeric suffixes (rc1 < rc2)
     */
    private static int comparePreRelease(String pre1, String pre2) {
        // Both null/empty = release versions = equal
        if (isEmpty(pre1) && isEmpty(pre2)) return 0;
        // Release version > any pre-release
        if (isEmpty(pre1)) return 1;
        if (isEmpty(pre2)) return -1;

        // Both have pre-release identifiers
        PreReleaseInfo info1 = parsePreRelease(pre1);
        PreReleaseInfo info2 = parsePreRelease(pre2);

        // Compare by type order (release > rc > beta > alpha > SNAPSHOT)
        int typeCompare = Integer.compare(info1.typeOrder, info2.typeOrder);
        if (typeCompare != 0) return typeCompare;

        // Same type - compare numeric suffix
        return Integer.compare(info1.number, info2.number);
    }

    /**
     * Parse a pre-release string into type and number.
     * Examples: "rc1" -> (rc, 1), "beta" -> (beta, 0), "R0.1" -> (r, 1), "SNAPSHOT" -> (snapshot, 0)
     */
    private static PreReleaseInfo parsePreRelease(String preRelease) {
        if (isEmpty(preRelease)) {
            return new PreReleaseInfo(5, 0); // release
        }

        String lower = preRelease.toLowerCase();

        // Check for known pre-release types
        if (lower.contains("snapshot")) {
            return new PreReleaseInfo(0, extractNumber(preRelease));
        }
        if (lower.contains("alpha")) {
            return new PreReleaseInfo(1, extractNumber(preRelease));
        }
        if (lower.contains("beta")) {
            return new PreReleaseInfo(2, extractNumber(preRelease));
        }
        if (lower.contains("rc") || lower.contains("cr")) { // rc or cr (release candidate)
            return new PreReleaseInfo(3, extractNumber(preRelease));
        }
        if (lower.startsWith("r") && lower.length() > 1 && Character.isDigit(lower.charAt(1))) {
            // Handle "R0.1" style (Paper uses -R0.1)
            // Extract all numbers for proper comparison (e.g., R0.1 -> [0, 1])
            return new PreReleaseInfo(3, extractPaperReleaseNumber(preRelease));
        }
        if (lower.contains("final") || lower.contains("release")) {
            return new PreReleaseInfo(5, 0);
        }

        // Unknown pre-release type - treat as lower than beta but higher than alpha
        return new PreReleaseInfo(1, extractNumber(preRelease));
    }

    /**
     * Extract number for comparison. For Paper-style "R0.1", "R0.2" etc.,
     * extract a composite number that preserves ordering: R0.1 -> 1, R0.2 -> 2, R1.0 -> 100.
     * For other formats, just use the first number found.
     */
    private static int extractPaperReleaseNumber(String str) {
        // Try to match "R" followed by numbers and dots
        Matcher m = Pattern.compile("^[rR](\\d+(?:\\.\\d+)*)").matcher(str);
        if (m.find()) {
            String versionPart = m.group(1); // e.g., "0.1", "0.2", "1.0"
            String[] parts = versionPart.split("\\.");
            int result = 0;
            int multiplier = 1;
            // Process from right to left (least significant first)
            for (int i = parts.length - 1; i >= 0; i--) {
                result += parseIntSafe(parts[i], 0) * multiplier;
                multiplier *= 100; // Allow up to 99 per segment
            }
            return result;
        }
        // Fallback: first number
        return extractNumber(str);
    }

    /**
     * Extract the first number from a string, return 0 if none found.
     */
    private static int extractNumber(String str) {
        Matcher m = Pattern.compile("\\d+").matcher(str);
        if (m.find()) {
            return parseIntSafe(m.group(), 0);
        }
        return 0;
    }

    private static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    private static int parseIntSafe(String str, int defaultValue) {
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Internal version representation.
     */
    private static class Version {
        final int major;
        final int minor;
        final int patch;
        final String preRelease;
        final String build;

        Version(int major, int minor, int patch, String preRelease, String build) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.preRelease = preRelease;
            this.build = build;
        }
    }

    /**
     * Pre-release type with ordering.
     */
    private static class PreReleaseInfo {
        final int typeOrder; // 0=SNAPSHOT, 1=alpha, 2=beta, 3=rc, 4=pre, 5=release
        final int number;

        PreReleaseInfo(int typeOrder, int number) {
            this.typeOrder = typeOrder;
            this.number = number;
        }
    }
}