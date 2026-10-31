package com.hanafubuki.updater.source;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.hanafubuki.updater.util.JarUtils;
import com.hanafubuki.updater.util.VersionUtils;

/**
 * Handler for Hangar API interactions (PaperMC's official plugin repository)
 * API: https://hangar.papermc.io/api/v1
 */
public class HangarSourceHandler implements SourceHandler {

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T t) throws IOException;
    }

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Gson gson;
    private final String userAgent;
    private final String serverVersion;
    private final Logger logger;
    private final ExecutorService executorService;
    private final int apiTimeoutSeconds;
    private final int downloadTimeoutSeconds;
    private final String updateFolderName;
    private final int maxRetries;
    private final int baseDelayMs;
    private final int maxDelayMs;
    private final Random random;

    /**
     * Constructor for HangarSourceHandler
     * @param httpClient The HTTP client to use for requests
     * @param serverVersion The server version to filter for (e.g., "1.21.9")
     * @param pluginVersion The version of the updater plugin
     * @param logger The logger to use for output
     * @param executorService The executor service to use for async operations
     * @param apiTimeoutSeconds API request timeout in seconds
     * @param downloadTimeoutSeconds Download timeout in seconds
     * @param updateFolderName The name of the update folder (from bukkit.yml settings.update-folder)
     * @param maxRetries Maximum number of retry attempts (default: 3)
     * @param baseDelayMs Base delay in milliseconds (default: 1000)
     * @param maxDelayMs Maximum delay in milliseconds (default: 10000)
     */
    public HangarSourceHandler(HttpClient httpClient, String serverVersion, String pluginVersion, Logger logger, ExecutorService executorService, int apiTimeoutSeconds, int downloadTimeoutSeconds, String updateFolderName, int maxRetries, int baseDelayMs, int maxDelayMs) {
        this.httpClient = httpClient;
        this.baseUrl = "https://hangar.papermc.io/api/v1";
        this.gson = new Gson();
        this.userAgent = "ByteMe6/hanafubuki-plugin-updater/" + pluginVersion;
        this.serverVersion = serverVersion;
        this.logger = logger;
        this.executorService = executorService;
        this.apiTimeoutSeconds = apiTimeoutSeconds;
        this.downloadTimeoutSeconds = downloadTimeoutSeconds;
        this.updateFolderName = updateFolderName != null ? updateFolderName : "update";
        this.maxRetries = maxRetries > 0 ? maxRetries : 3;
        this.baseDelayMs = baseDelayMs > 0 ? baseDelayMs : 1000;
        this.maxDelayMs = maxDelayMs > 0 ? maxDelayMs : 10000;
        this.random = new Random();
    }

    /**
     * Backward compatible constructor without retry parameters
     */
    public HangarSourceHandler(HttpClient httpClient, String serverVersion, String pluginVersion, Logger logger, ExecutorService executorService, int apiTimeoutSeconds, int downloadTimeoutSeconds, String updateFolderName) {
        this(httpClient, serverVersion, pluginVersion, logger, executorService, apiTimeoutSeconds, downloadTimeoutSeconds, updateFolderName, 3, 1000, 10000);
    }

    @Override
    public String getSourceName() {
        return "hangar";
    }

    @Override
    public boolean validateConfig(String pluginName, Map<String, Object> pluginDetails) {
        Object projectSlugObj = pluginDetails.get("project_slug");
        if (projectSlugObj == null || projectSlugObj.toString().trim().isEmpty()) {
            logger.severe("Plugin '" + pluginName + "' (hangar source) is missing required field 'project_slug' (format: owner/slug)");
            return false;
        }
        String projectSlug = projectSlugObj.toString().trim();
        if (!projectSlug.contains("/")) {
            logger.severe("Plugin '" + pluginName + "' (hangar source) has invalid project_slug format: '" + projectSlug + "' (must be owner/slug)");
            return false;
        }
        return true;
    }

    @Override
    public String getLatestVersion(String pluginName, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        try {
            String projectSlug = getProjectSlug(pluginDetails);
            if (projectSlug == null) {
                throw new IOException("Could not determine project slug for plugin: " + pluginName);
            }

            String versionsUrl = baseUrl + "/projects/" + projectSlug + "/versions";
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(versionsUrl))
                    .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET();

            String responseBody = executeWithRetry(requestBuilder, response -> {
                if (response.statusCode() == 200) {
                    return response.body();
                } else {
                    throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
                }
            }, "getLatestVersion for " + pluginName);

            JsonObject versionsResponse = gson.fromJson(responseBody, JsonObject.class);
            JsonArray versionsJson = versionsResponse.getAsJsonArray("result");

            // Find the latest version compatible with Paper and server version
            for (JsonElement element : versionsJson) {
                JsonObject versionObj = element.getAsJsonObject();
                String versionName = versionObj.get("name").getAsString();

                // Check if this version supports Paper platform
                JsonObject downloads = versionObj.getAsJsonObject("downloads");
                if (downloads == null || !downloads.has("PAPER")) {
                    continue; // Skip non-Paper versions
                }

                // Check if this version supports server version
                JsonObject platformDependencies = versionObj.getAsJsonObject("platformDependencies");
                if (platformDependencies != null && platformDependencies.has("PAPER")) {
                    JsonArray paperVersions = platformDependencies.getAsJsonArray("PAPER");
                    boolean hasServerVersion = false;
                    if (paperVersions != null) {
                        for (JsonElement pv : paperVersions) {
                            if (versionMatches(pv.getAsString())) {
                                hasServerVersion = true;
                                break;
                            }
                        }
                    }
                    if (!hasServerVersion) {
                        continue;
                    }
                }

                // Prefer stable/release channels over beta/unstable
                JsonObject channel = versionObj.getAsJsonObject("channel");
                if (channel != null) {
                    String channelName = channel.get("name").getAsString();
                    JsonArray flags = channel.getAsJsonArray("flags");
                    boolean isUnstable = false;
                    if (flags != null) {
                        for (JsonElement flag : flags) {
                            if ("UNSTABLE".equals(flag.getAsString())) {
                                isUnstable = true;
                                break;
                            }
                        }
                    }
                    // For latest version, prefer non-unstable
                    if (!isUnstable) {
                        return versionName;
                    }
                } else {
                    return versionName;
                }
            }

            // If no stable version found, return first compatible version
            for (JsonElement element : versionsJson) {
                JsonObject versionObj = element.getAsJsonObject();
                JsonObject downloads = versionObj.getAsJsonObject("downloads");
                if (downloads != null && downloads.has("PAPER")) {
                    JsonObject platformDependencies = versionObj.getAsJsonObject("platformDependencies");
                    if (platformDependencies != null && platformDependencies.has("PAPER")) {
                        JsonArray paperVersions = platformDependencies.getAsJsonArray("PAPER");
                        boolean hasServerVersion = false;
                        if (paperVersions != null) {
                            for (JsonElement pv : paperVersions) {
                                if (versionMatches(pv.getAsString())) {
                                    hasServerVersion = true;
                                    break;
                                }
                            }
                        }
                        if (hasServerVersion) {
                            return versionObj.get("name").getAsString();
                        }
                    }
                }
            }

        } catch (Exception e) {
            throw new IOException("Failed to get latest version from Hangar: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Check if a platform version string matches the server version.
     * Supports exact match and version ranges (e.g., "1.21.4-1.21.11")
     */
    private boolean versionMatches(String platformVersion) {
        if (platformVersion == null) return false;
        
        // Exact match
        if (serverVersion.equals(platformVersion)) return true;
        
        // Check if it's a range (e.g., "1.21.4-26.3")
        if (platformVersion.contains("-")) {
            String[] parts = platformVersion.split("-");
            if (parts.length == 2) {
                return versionInRange(serverVersion, parts[0], parts[1]);
            }
        }
        
        return false;
    }

    /**
     * Check if a version is within a range (inclusive).
     * Simple semantic version comparison.
     */
    private boolean versionInRange(String version, String min, String max) {
        return compareVersions(version, min) >= 0 && compareVersions(version, max) <= 0;
    }

    /**
     * Simple version comparison for semantic versions.
     * Returns negative if v1 < v2, 0 if equal, positive if v1 > v2.
     */
    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("[.-]");
        String[] parts2 = v2.split("[.-]");
        int length = Math.max(parts1.length, parts2.length);
        
        for (int i = 0; i < length; i++) {
            int n1 = i < parts1.length ? parseIntSafe(parts1[i]) : 0;
            int n2 = i < parts2.length ? parseIntSafe(parts2[i]) : 0;
            if (n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }

    private int parseIntSafe(String str) {
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public String getDownloadUrl(String pluginName, String version, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        try {
            String projectSlug = getProjectSlug(pluginDetails);
            if (projectSlug == null) {
                throw new IOException("Could not determine project slug for plugin: " + pluginName);
            }

            String versionsUrl = baseUrl + "/projects/" + projectSlug + "/versions";
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(versionsUrl))
                    .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET();

            String responseBody = executeWithRetry(requestBuilder, response -> {
                if (response.statusCode() == 200) {
                    return response.body();
                } else {
                    throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
                }
            }, "getDownloadUrl for " + pluginName);

            JsonObject versionsResponse = gson.fromJson(responseBody, JsonObject.class);
            JsonArray versionsJson = versionsResponse.getAsJsonArray("result");

            for (JsonElement element : versionsJson) {
                JsonObject versionObj = element.getAsJsonObject();
                String versionName = versionObj.get("name").getAsString();

                if (version.equals(versionName)) {
                    JsonObject downloads = versionObj.getAsJsonObject("downloads");
                    if (downloads != null && downloads.has("PAPER")) {
                        JsonObject paperDownload = downloads.getAsJsonObject("PAPER");
                        if (paperDownload.has("downloadUrl")) {
                            return paperDownload.get("downloadUrl").getAsString();
                        }
                    }
                }
            }

        } catch (Exception e) {
            throw new IOException("Failed to get download URL from Hangar: " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    public void downloadAndInstall(String pluginName, String downloadUrl, Path installedJarPath, Map<String, Object> pluginDetails) throws IOException {
        try {
            // Determine target filename (same as installed jar)
            String targetFileName = installedJarPath.getFileName().toString();
            Path updateDir = installedJarPath.getParent().resolve(updateFolderName);
            Files.createDirectories(updateDir);

            Path tempFile = updateDir.resolve(targetFileName + ".part");
            Path targetFile = updateDir.resolve(targetFileName);

            // Download the file
            logger.info("Downloading " + pluginName + " from Hangar: " + downloadUrl);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .timeout(Duration.ofSeconds(downloadTimeoutSeconds))
                    .header("User-Agent", userAgent)
                    .GET();

            executeDownloadWithRetry(requestBuilder, tempFile, "downloadAndInstall for " + pluginName);

            // Verify SHA-256 hash if provided
            String expectedSha256 = getExpectedChecksum(pluginName, (String) pluginDetails.get("version"), pluginDetails, installedJarPath);
            if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                String actualSha256 = computeSha256(tempFile);
                if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                    Files.deleteIfExists(tempFile);
                    throw new IOException("SHA-256 mismatch for " + pluginName + ": expected " + expectedSha256 + ", got " + actualSha256);
                }
            }

            // Verify plugin.yml name inside JAR matches installed plugin name
            String jarPluginName = JarUtils.getPluginNameFromJar(tempFile);
            if (!jarPluginName.equalsIgnoreCase(pluginName)) {
                Files.deleteIfExists(tempFile);
                throw new IOException("Plugin name mismatch: JAR contains '" + jarPluginName + "', expected '" + pluginName + "'");
            }

            // Atomic move to target location
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            logger.info("Plugin " + pluginName + " downloaded and verified successfully to: " + targetFile);

        } catch (Exception e) {
            throw new IOException("Failed to download and install plugin from Hangar: " + e.getMessage(), e);
        }
    }

    @Override
    public String getExpectedChecksum(String pluginName, String version, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        try {
            String projectSlug = getProjectSlug(pluginDetails);
            if (projectSlug == null) {
                throw new IOException("Could not determine project slug for plugin: " + pluginName);
            }

            String versionsUrl = baseUrl + "/projects/" + projectSlug + "/versions";
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(versionsUrl))
                    .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET();

            String responseBody = executeWithRetry(requestBuilder, response -> {
                if (response.statusCode() == 200) {
                    return response.body();
                } else {
                    throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
                }
            }, "getExpectedChecksum for " + pluginName);

            JsonObject versionsResponse = gson.fromJson(responseBody, JsonObject.class);
            JsonArray versionsJson = versionsResponse.getAsJsonArray("result");

            for (JsonElement element : versionsJson) {
                JsonObject versionObj = element.getAsJsonObject();
                String versionName = versionObj.get("name").getAsString();

                if (version.equals(versionName)) {
                    JsonObject downloads = versionObj.getAsJsonObject("downloads");
                    if (downloads != null && downloads.has("PAPER")) {
                        JsonObject paperDownload = downloads.getAsJsonObject("PAPER");
                        JsonObject fileInfo = paperDownload.getAsJsonObject("fileInfo");
                        if (fileInfo != null && fileInfo.has("sha256Hash")) {
                            return fileInfo.get("sha256Hash").getAsString();
                        }
                    }
                }
            }

        } catch (Exception e) {
            throw new IOException("Failed to get expected SHA-256 from Hangar: " + e.getMessage(), e);
        }
        return null;
    }

    private String getProjectSlug(Map<String, Object> pluginDetails) {
        Object projectSlugObj = pluginDetails.get("project_slug");
        if (projectSlugObj != null) {
            String projectSlug = projectSlugObj.toString().trim();
            if (!projectSlug.isEmpty()) {
                return projectSlug;
            }
        }
        return null;
    }

    private <T> T executeWithRetry(HttpRequest.Builder requestBuilder, ThrowingFunction<HttpResponse<String>, T> responseHandler, String operationName) throws IOException {
        HttpRequest request = requestBuilder.build();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();

                if (shouldRetry(statusCode)) {
                    if (attempt < maxRetries) {
                        long delay = calculateDelay(attempt);
                        logger.warning(String.format("Retrying %s (attempt %d/%d) after %dms - HTTP %d",
                                operationName, attempt + 1, maxRetries, delay, statusCode));
                        Thread.sleep(delay);
                        continue;
                    } else {
                        throw new IOException(String.format("Max retries exceeded for %s: HTTP %d - %s",
                                operationName, statusCode, response.body()));
                    }
                }

                return responseHandler.apply(response);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Request interrupted: " + operationName, e);
            } catch (IOException e) {
                if (attempt < maxRetries) {
                    long delay = calculateDelay(attempt);
                    logger.warning(String.format("Retrying %s (attempt %d/%d) after %dms - %s",
                            operationName, attempt + 1, maxRetries, delay, e.getMessage()));
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Request interrupted: " + operationName, ie);
                    }
                    continue;
                }
                throw e;
            }
        }

        throw new IOException("Unexpected end of retry loop for: " + operationName);
    }

    private void executeDownloadWithRetry(HttpRequest.Builder requestBuilder, Path targetPath, String operationName) throws IOException {
        HttpRequest request = requestBuilder.build();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(targetPath));
                int statusCode = response.statusCode();

                if (shouldRetry(statusCode)) {
                    if (attempt < maxRetries) {
                        long delay = calculateDelay(attempt);
                        logger.warning(String.format("Retrying %s (attempt %d/%d) after %dms - HTTP %d",
                                operationName, attempt + 1, maxRetries, delay, statusCode));
                        Thread.sleep(delay);
                        Files.deleteIfExists(targetPath);
                        continue;
                    } else {
                        Files.deleteIfExists(targetPath);
                        throw new IOException(String.format("Max retries exceeded for %s: HTTP %d",
                                operationName, statusCode));
                    }
                }

                if (statusCode != 200) {
                    Files.deleteIfExists(targetPath);
                    throw new IOException("HTTP " + statusCode + " downloading " + operationName);
                }
                return;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Files.deleteIfExists(targetPath);
                throw new IOException("Request interrupted: " + operationName, e);
            } catch (IOException e) {
                if (attempt < maxRetries) {
                    long delay = calculateDelay(attempt);
                    logger.warning(String.format("Retrying %s (attempt %d/%d) after %dms - %s",
                            operationName, attempt + 1, maxRetries, delay, e.getMessage()));
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Request interrupted: " + operationName, ie);
                    }
                    Files.deleteIfExists(targetPath);
                    continue;
                }
                Files.deleteIfExists(targetPath);
                throw e;
            }
        }

        throw new IOException("Unexpected end of retry loop for: " + operationName);
    }

    private boolean shouldRetry(int statusCode) {
        return statusCode == 429 || (statusCode >= 500 && statusCode < 600);
    }

    private long calculateDelay(int attempt) {
        long exponentialDelay = baseDelayMs * (1L << attempt);
        long jitter = random.nextInt((int) Math.min(baseDelayMs, 1000));
        return Math.min(exponentialDelay + jitter, maxDelayMs);
    }

    private String computeSha256(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
    }
}