package com.hanafubuki.updater.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Common interface for all source handlers (Modrinth, GitHub, Hangar, SpigotMC, Local, Custom HTTP).
 * Each handler implements the logic to communicate with its specific source API.
 */
public interface SourceHandler {

    /**
     * Get the latest version of a plugin from the source.
     *
     * @param pluginName Name of the plugin
     * @param pluginDetails Plugin configuration details from config.yml
     * @param installedJarPath Path to the currently installed JAR file (for hash-based lookup)
     * @return Latest version string or null if not found
     * @throws IOException If there's an error during API request
     */
    String getLatestVersion(String pluginName, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException;

    /**
     * Get the download URL for a specific version of a plugin.
     *
     * @param pluginName Name of the plugin
     * @param version Version number to download
     * @param pluginDetails Plugin configuration details from config.yml
     * @param installedJarPath Path to the currently installed JAR file
     * @return Download URL string or null if not found
     * @throws IOException If there's an error during API request
     */
    String getDownloadUrl(String pluginName, String version, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException;

    /**
     * Download and install a plugin from the source.
     * Downloads to plugins/{update-folder}/<same filename>.part,
     * verifies hash (if applicable) and plugin.yml name, then atomic move.
     *
     * @param pluginName Name of the plugin
     * @param downloadUrl URL to download the plugin from
     * @param installedJarPath Path to the currently installed JAR file
     * @param pluginDetails Plugin configuration details (for verification data like expected hash)
     * @throws IOException If there's an error during download/installation or verification fails
     */
    void downloadAndInstall(String pluginName, String downloadUrl, Path installedJarPath, Map<String, Object> pluginDetails) throws IOException;

    /**
     * Get the expected checksum/hash for a specific version (if supported by source).
     *
     * @param pluginName Name of the plugin
     * @param version Version number
     * @param pluginDetails Plugin configuration details
     * @param installedJarPath Path to the currently installed JAR file
     * @return Expected checksum string or null if not supported/not found
     * @throws IOException If there's an error during API request
     */
    String getExpectedChecksum(String pluginName, String version, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException;

    /**
     * Get the name of this source (e.g., "modrinth", "github", "hangar", "spigotmc", "local", "custom").
     *
     * @return Source identifier string
     */
    String getSourceName();

    /**
     * Validate plugin configuration for this source.
     *
     * @param pluginName Name of the plugin
     * @param pluginDetails Plugin configuration details
     * @return true if valid, false if invalid
     */
    boolean validateConfig(String pluginName, Map<String, Object> pluginDetails);
}