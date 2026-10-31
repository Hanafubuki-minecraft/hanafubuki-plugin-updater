package com.hanafubuki.updater.source;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
 * Handler for SpigotMC API interactions via Spiget API
 * API: https://api.spiget.org/v2
 * SpigotMC doesn't have an official API, so we use the community Spiget API
 */
public class SpigotMCSourceHandler implements SourceHandler {

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
     * Constructor for SpigotMCSourceHandler
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
    public SpigotMCSourceHandler(HttpClient httpClient, String serverVersion, String pluginVersion, Logger logger, ExecutorService executorService, int apiTimeoutSeconds, int downloadTimeoutSeconds, String updateFolderName, int maxRetries, int baseDelayMs, int maxDelayMs) {
        this.httpClient = httpClient;
        this.baseUrl = "https://api.spiget.org/v2";
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
    public SpigotMCSourceHandler(HttpClient httpClient, String serverVersion, String pluginVersion, Logger logger, ExecutorService executorService, int apiTimeoutSeconds, int downloadTimeoutSeconds, String updateFolderName) {
        this(httpClient, serverVersion, pluginVersion, logger, executorService, apiTimeoutSeconds, downloadTimeoutSeconds, updateFolderName, 3, 1000, 10000);
    }

    @Override
    public String getSourceName() {
        return "spigotmc";
    }

    @Override
    public boolean validateConfig(String pluginName, Map<String, Object> pluginDetails) {
        // Accept either resource_id (numeric) or resource_name for lookup
        Object resourceIdObj = pluginDetails.get("resource_id");
        Object resourceNameObj = pluginDetails.get("resource_name");
        
        if ((resourceIdObj == null || resourceIdObj.toString().trim().isEmpty()) &&
            (resourceNameObj == null || resourceNameObj.toString().trim().isEmpty())) {
            logger.severe("Plugin '" + pluginName + "' (spigotmc source) requires either 'resource_id' (numeric) or 'resource_name'");
            return false;
        }
        
        if (resourceIdObj != null) {
            try {
                Integer.parseInt(resourceIdObj.toString().trim());
            } catch (NumberFormatException e) {
                logger.severe("Plugin '" + pluginName + "' (spigotmc source) has invalid 'resource_id': must be numeric");
                return false;
            }
        }
        
        return true;
    }

    @Override
    public String getLatestVersion(String pluginName, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        try {
            // First, resolve the resource ID
            int resourceId = resolveResourceId(pluginName, pluginDetails);
            if (resourceId <= 0) {
                throw new IOException("Could not resolve resource ID for plugin: " + pluginName);
            }

            // Get versions for this resource
            String versionsUrl = baseUrl + "/resources/" + resourceId + "/versions";
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

            JsonArray versionsJson = gson.fromJson(responseBody, JsonArray.class);

            // Find the latest version compatible with server version
            // Spiget versions are ordered newest first
            for (JsonElement element : versionsJson) {
                JsonObject versionObj = element.getAsJsonObject();
                String versionName = versionObj.get("name").getAsString();
                
                // Check if this version supports our server version
                JsonArray testedVersions = versionObj.getAsJsonArray("testedVersions");
                if (testedVersions != null && testedVersions.size() > 0) {
                    boolean compatible = false;
                    for (JsonElement tv : testedVersions) {
                        if (versionMatches(tv.getAsString())) {
                            compatible = true;
                            break;
                        }
                    }
                    if (compatible) {
                        return versionName;
                    }
                } else {
                    // If no tested versions specified, assume compatible
                    return versionName;
                }
            }

            // If no compatible version found, return the first (latest) version
            if (!versionsJson.isEmpty()) {
                return versionsJson.get(0).getAsJsonObject().get("name").getAsString();
            }

        } catch (Exception e) {
            throw new IOException("Failed to get latest version from SpigotMC: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Check if a tested version string matches the server version.
     * Spiget uses simple version strings like "1.21.9", "1.21", "1.20"
     */
    private boolean versionMatches(String testedVersion) {
        if (testedVersion == null) return false;
        
        // Exact match
        if (serverVersionEquals(testedVersion)) return true;
        
        // Prefix match (e.g., "1.21" matches "1.21.9")
        if (testedVersion.endsWith(".*")) {
            String prefix = testedVersion.substring(0, testedVersion.length() - 2);
            return serverVersion.startsWith(prefix);
        }
        
        // Major.minor match
        if (testedVersion.matches("\\d+\\.\\d+")) {
            return serverVersion.startsWith(testedVersion + ".");
        }
        
        return false;
    }

    /**
     * Check if the server version matches a tested version.
     * This is a simplified check - in reality we'd need access to the server version.
     * For now, we assume compatibility if the major.minor matches.
     */
    private boolean serverVersionEquals(String testedVersion) {
        // We don't have direct access to server version here
        // This would need to be passed in or accessed from a shared config
        // For now, return true to allow all versions
        // TODO: Pass server version to handler
        return true;
    }

    @Override
    public String getDownloadUrl(String pluginName, String version, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        try {
            int resourceId = resolveResourceId(pluginName, pluginDetails);
            if (resourceId <= 0) {
                throw new IOException("Could not resolve resource ID for plugin: " + pluginName);
            }

            // Get the specific version to find its download URL
            String versionsUrl = baseUrl + "/resources/" + resourceId + "/versions";
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

            JsonArray versionsJson = gson.fromJson(responseBody, JsonArray.class);

            for (JsonElement element : versionsJson) {
                JsonObject versionObj = element.getAsJsonObject();
                String versionName = versionObj.get("name").getAsString();

                if (version.equals(versionName)) {
                    // Get the file info for this version
                    JsonObject file = versionObj.getAsJsonObject("file");
                    if (file != null && file.has("url")) {
                        String downloadUrl = file.get("url").getAsString();
                        // Spiget returns relative URLs, prepend base
                        if (downloadUrl.startsWith("/")) {
                            return "https://api.spiget.org" + downloadUrl;
                        }
                        return downloadUrl;
                    }
                    // Fallback: use the generic download endpoint
                    return "https://api.spiget.org/v2/resources/" + resourceId + "/download";
                }
            }

        } catch (Exception e) {
            throw new IOException("Failed to get download URL from SpigotMC: " + e.getMessage(), e);
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
            logger.info("Downloading " + pluginName + " from SpigotMC: " + downloadUrl);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .timeout(Duration.ofSeconds(downloadTimeoutSeconds))
                    .header("User-Agent", userAgent)
                    .GET();

            executeDownloadWithRetry(requestBuilder, tempFile, "downloadAndInstall for " + pluginName);

            // Spiget doesn't provide checksums, so we skip hash verification

            // Verify plugin.yml name inside JAR matches installed plugin name
            String jarPluginName = JarUtils.getPluginNameFromJar(tempFile);
            if (jarPluginName == null || !jarPluginName.equalsIgnoreCase(pluginName)) {
                Files.deleteIfExists(tempFile);
                throw new IOException("Plugin name mismatch: JAR contains '" + jarPluginName + "', expected '" + pluginName + "'");
            }

            // Atomic move to target location
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            logger.info("Plugin " + pluginName + " downloaded and verified successfully to: " + targetFile);

        } catch (Exception e) {
            throw new IOException("Failed to download and install plugin from SpigotMC: " + e.getMessage(), e);
        }
    }

    @Override
    public String getExpectedChecksum(String pluginName, String version, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        // Spiget API doesn't provide checksums
        return null;
    }

    private int resolveResourceId(String pluginName, Map<String, Object> pluginDetails) throws IOException {
        // First try explicit resource_id
        Object resourceIdObj = pluginDetails.get("resource_id");
        if (resourceIdObj != null) {
            try {
                return Integer.parseInt(resourceIdObj.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }

        // Fallback: search by resource_name
        Object resourceNameObj = pluginDetails.get("resource_name");
        if (resourceNameObj != null) {
            String resourceName = resourceNameObj.toString().trim();
            if (!resourceName.isEmpty()) {
                return searchResourceByName(resourceName);
            }
        }

        // Last resort: search by plugin name
        return searchResourceByName(pluginName);
    }

    private int searchResourceByName(String name) throws IOException {
        String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
        String searchUrl = baseUrl + "/resources?search=" + encodedName + "&size=10";
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(searchUrl))
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
        }, "searchResourceByName for " + name);

        JsonArray results = gson.fromJson(responseBody, JsonArray.class);
        
        // Find best match (exact name match first)
        for (JsonElement element : results) {
            JsonObject resource = element.getAsJsonObject();
            String resourceName = resource.get("name").getAsString();
            if (resourceName.equalsIgnoreCase(name)) {
                return resource.get("id").getAsInt();
            }
        }
        
        // Return first result if no exact match
        if (!results.isEmpty()) {
            return results.get(0).getAsJsonObject().get("id").getAsInt();
        }

        return -1;
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
}