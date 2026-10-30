package com.hanafubuki.updater.util;

import java.util.Arrays;
import java.util.List;

/**
 * Utility class for version comparison and handling
 */
public class VersionUtils {
    
    /**
     * Check if a new version is newer than the current version
     * @param newVersion The new version string to compare
     * @param currentVersion The current version string to compare against
     * @return true if newVersion is newer, false otherwise
     */
    public static boolean isVersionNewer(String newVersion, String currentVersion) {
        if (newVersion == null || currentVersion == null) {
            return false;
        }
        
        // Handle special cases for version strings
        if ("latest".equalsIgnoreCase(newVersion)) {
            return true;
        }
        
        List<String> newParts = Arrays.asList(newVersion.split("\\."));
        List<String> currentParts = Arrays.asList(currentVersion.split("\\."));
        
        int maxLength = Math.max(newParts.size(), currentParts.size());
        
        for (int i = 0; i < maxLength; i++) {
            int newPart = i < newParts.size() ? parseInt(newParts.get(i)) : 0;
            int currentPart = i < currentParts.size() ? parseInt(currentParts.get(i)) : 0;
            
            if (newPart > currentPart) {
                return true;
            } else if (newPart < currentPart) {
                return false;
            }
        }
        
        // Versions are equal
        return false;
    }
    
    /**
     * Parse a version part to integer, handling special cases like "1.0.0-SNAPSHOT"
     * @param part The version part string
     * @return The parsed integer value
     */
    private static int parseInt(String part) {
        try {
            // Remove any suffixes like -SNAPSHOT, -beta, etc.
            int dashIndex = part.indexOf('-');
            if (dashIndex > 0) {
                part = part.substring(0, dashIndex);
            }
            
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            // If parsing fails, return 0 for safety
            return 0;
        }
    }
    
    /**
     * Check if a plugin version meets the minimum required version
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
        
        return isVersionNewer(pluginVersion, minVersion) || pluginVersion.equals(minVersion);
    }
}