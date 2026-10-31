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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.hanafubuki.updater.util.JarUtils;
import com.jayway.jsonpath.JsonPath;

/**
 * Handler for custom HTTP/REST source
 * Configurable REST endpoint with JSON response schema
 * Supports custom headers, auth (Bearer, Basic)
 * JSONPath or field mapping for: version, download_url, checksum
 */
public class CustomHTTPSourceHandler implements SourceHandler {

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T t) throws IOException;
    }

    private final HttpClient httpClient;
    private final Gson gson;
    private final String userAgent;
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
     * Constructor for CustomHTTPSourceHandler
     * @param httpClient The HTTP client to use for requests
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
    public CustomHTTPSourceHandler(HttpClient httpClient, String pluginVersion, Logger logger, ExecutorService executorService, int apiTimeoutSeconds, int downloadTimeoutSeconds, String updateFolderName, int maxRetries, int baseDelayMs, int maxDelayMs) {
        this.httpClient = httpClient;
        this.gson = new Gson();
        this.userAgent = "ByteMe6/hanafubuki-plugin-updater/" + pluginVersion;
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
    public CustomHTTPSourceHandler(HttpClient httpClient, String pluginVersion, Logger logger, ExecutorService executorService, int apiTimeoutSeconds, int downloadTimeoutSeconds, String updateFolderName) {
        this(httpClient, pluginVersion, logger, executorService, apiTimeoutSeconds, downloadTimeoutSeconds, updateFolderName, 3, 1000, 10000);
    }

    @Override
    public String getSourceName() {
        return "custom";
    }

    @Override
    public boolean validateConfig(String pluginName, Map<String, Object> pluginDetails) {
        Object urlObj = pluginDetails.get("url");
        if (urlObj == null || urlObj.toString().trim().isEmpty()) {
            logger.severe("Plugin '" + pluginName + "' (custom source) is missing required field 'url' (REST endpoint)");
            return false;
        }

        // Validate JSONPath expressions if provided
        String[] pathFields = {"version_path", "download_url_path", "checksum_path"};
        for (String field : pathFields) {
            Object pathObj = pluginDetails.get(field);
            if (pathObj != null) {
                try {
                    JsonPath.compile(pathObj.toString());
                } catch (Exception e) {
                    logger.severe("Plugin '" + pluginName + "' (custom source) has invalid JSONPath for '" + field + "': " + e.getMessage());
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public String getLatestVersion(String pluginName, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        try {
            String url = getUrl(pluginDetails);
            Map<String, String> headers = getHeaders(pluginDetails);

            HttpRequest.Builder requestBuilder = buildRequest(url, headers, false);
            
            String responseBody = executeWithRetry(requestBuilder, response -> {
                if (response.statusCode() == 200) {
                    return response.body();
                } else {
                    throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
                }
            }, "getLatestVersion for " + pluginName);

            // Extract version using JSONPath or direct field
            String versionPath = getVersionPath(pluginDetails);
            if (versionPath != null) {
                try {
                    Object version = JsonPath.read(responseBody, versionPath);
                    if (version != null) {
                        return version.toString();
                    }
                } catch (Exception e) {
                    logger.warning("Failed to extract version from JSONPath '" + versionPath + "': " + e.getMessage());
                }
            }

            // Fallback: try common field names
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            String[] versionFields = {"version", "latest_version", "tag_name", "name", "version_number"};
            for (String field : versionFields) {
                if (json.has(field)) {
                    return json.get(field).getAsString();
                }
            }

        } catch (Exception e) {
            throw new IOException("Failed to get latest version from custom source: " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    public String getDownloadUrl(String pluginName, String version, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        try {
            String url = getUrl(pluginDetails);
            Map<String, String> headers = getHeaders(pluginDetails);

            HttpRequest.Builder requestBuilder = buildRequest(url, headers, false);
            
            String responseBody = executeWithRetry(requestBuilder, response -> {
                if (response.statusCode() == 200) {
                    return response.body();
                } else {
                    throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
                }
            }, "getDownloadUrl for " + pluginName);

            // Extract download URL using JSONPath or direct field
            String downloadUrlPath = getDownloadUrlPath(pluginDetails);
            if (downloadUrlPath != null) {
                try {
                    Object downloadUrl = JsonPath.read(responseBody, downloadUrlPath);
                    if (downloadUrl != null) {
                        return downloadUrl.toString();
                    }
                } catch (Exception e) {
                    logger.warning("Failed to extract download URL from JSONPath '" + downloadUrlPath + "': " + e.getMessage());
                }
            }

            // Fallback: try common field names
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            String[] downloadFields = {"download_url", "url", "browser_download_url", "asset_url", "file_url"};
            for (String field : downloadFields) {
                if (json.has(field)) {
                    return json.get(field).getAsString();
                }
            }

            // If response has assets array, try to find matching asset
            if (json.has("assets")) {
                JsonElement assets = json.get("assets");
                if (assets.isJsonArray()) {
                    for (JsonElement asset : assets.getAsJsonArray()) {
                        JsonObject assetObj = asset.getAsJsonObject();
                        if (assetObj.has("browser_download_url")) {
                            return assetObj.get("browser_download_url").getAsString();
                        }
                        if (assetObj.has("url")) {
                            return assetObj.get("url").getAsString();
                        }
                    }
                }
            }

        } catch (Exception e) {
            throw new IOException("Failed to get download URL from custom source: " + e.getMessage(), e);
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
            logger.info("Downloading " + pluginName + " from custom source: " + downloadUrl);

            Map<String, String> headers = getHeaders(pluginDetails);
            HttpRequest.Builder requestBuilder = buildDownloadRequest(downloadUrl, headers);
            
            executeDownloadWithRetry(requestBuilder, tempFile, "downloadAndInstall for " + pluginName);

            // Verify checksum if available
            String expectedChecksum = getExpectedChecksum(pluginName, (String) pluginDetails.get("version"), pluginDetails, installedJarPath);
            if (expectedChecksum != null && !expectedChecksum.isEmpty()) {
                String actualChecksum = computeSha256(tempFile);
                if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
                    Files.deleteIfExists(tempFile);
                    throw new IOException("SHA-256 mismatch for " + pluginName + ": expected " + expectedChecksum + ", got " + actualChecksum);
                }
            }

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
            throw new IOException("Failed to download and install plugin from custom source: " + e.getMessage(), e);
        }
    }

    @Override
    public String getExpectedChecksum(String pluginName, String version, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        try {
            // First check if checksum is explicitly provided in config
            Object checksumObj = pluginDetails.get("checksum");
            if (checksumObj != null) {
                return checksumObj.toString().trim();
            }

            // Try to extract from API response
            String url = getUrl(pluginDetails);
            Map<String, String> headers = getHeaders(pluginDetails);

            HttpRequest.Builder requestBuilder = buildRequest(url, headers, false);
            
            String responseBody = executeWithRetry(requestBuilder, response -> {
                if (response.statusCode() == 200) {
                    return response.body();
                } else {
                    throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
                }
            }, "getExpectedChecksum for " + pluginName);

            // Extract checksum using JSONPath
            String checksumPath = getChecksumPath(pluginDetails);
            if (checksumPath != null) {
                try {
                    Object checksum = JsonPath.read(responseBody, checksumPath);
                    if (checksum != null) {
                        return checksum.toString();
                    }
                } catch (Exception e) {
                    logger.warning("Failed to extract checksum from JSONPath '" + checksumPath + "': " + e.getMessage());
                }
            }

            // Fallback: try common field names
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            String[] checksumFields = {"sha256", "sha512", "checksum", "hash", "digest"};
            for (String field : checksumFields) {
                if (json.has(field)) {
                    return json.get(field).getAsString();
                }
            }

            // Check nested hashes object (like Modrinth)
            if (json.has("hashes")) {
                JsonObject hashes = json.getAsJsonObject("hashes");
                if (hashes.has("sha256")) return hashes.get("sha256").getAsString();
                if (hashes.has("sha512")) return hashes.get("sha512").getAsString();
            }

        } catch (Exception e) {
            throw new IOException("Failed to get expected checksum from custom source: " + e.getMessage(), e);
        }
        return null;
    }

    private String getUrl(Map<String, Object> pluginDetails) {
        Object urlObj = pluginDetails.get("url");
        return urlObj != null ? urlObj.toString().trim() : null;
    }

    private String getVersionPath(Map<String, Object> pluginDetails) {
        Object obj = pluginDetails.get("version_path");
        return obj != null ? obj.toString().trim() : null;
    }

    private String getDownloadUrlPath(Map<String, Object> pluginDetails) {
        Object obj = pluginDetails.get("download_url_path");
        return obj != null ? obj.toString().trim() : null;
    }

    private String getChecksumPath(Map<String, Object> pluginDetails) {
        Object obj = pluginDetails.get("checksum_path");
        return obj != null ? obj.toString().trim() : null;
    }

    private Map<String, String> getHeaders(Map<String, Object> pluginDetails) {
        Map<String, String> headers = new java.util.HashMap<>();
        
        // Add custom headers from config
        Object headersObj = pluginDetails.get("headers");
        if (headersObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> customHeaders = (Map<String, Object>) headersObj;
            for (Map.Entry<String, Object> entry : customHeaders.entrySet()) {
                headers.put(entry.getKey(), entry.getValue().toString());
            }
        }

        // Handle authentication
        Object authObj = pluginDetails.get("auth");
        if (authObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> auth = (Map<String, Object>) authObj;
            String type = (String) auth.get("type");
            
            if ("bearer".equalsIgnoreCase(type)) {
                String token = (String) auth.get("token");
                if (token != null && !token.isEmpty()) {
                    headers.put("Authorization", "Bearer " + token);
                }
            } else if ("basic".equalsIgnoreCase(type)) {
                String username = (String) auth.get("username");
                String password = (String) auth.get("password");
                if (username != null && password != null) {
                    String credentials = username + ":" + password;
                    String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                    headers.put("Authorization", "Basic " + encoded);
                }
            } else if ("api_key".equalsIgnoreCase(type)) {
                String key = (String) auth.get("key");
                String header = (String) auth.getOrDefault("header", "X-API-Key");
                if (key != null) {
                    headers.put(header, key);
                }
            }
        }

        return headers;
    }

    private HttpRequest.Builder buildRequest(String url, Map<String, String> headers, boolean isDownload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(isDownload ? downloadTimeoutSeconds : apiTimeoutSeconds))
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .GET();

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        return builder;
    }

    private HttpRequest.Builder buildDownloadRequest(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(downloadTimeoutSeconds))
                .header("User-Agent", userAgent)
                .GET();

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        return builder;
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