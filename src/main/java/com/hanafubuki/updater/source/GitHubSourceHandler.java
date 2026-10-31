package com.hanafubuki.updater.source;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
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

/**
 * Handler for GitHub API interactions
 * This class manages communication with the GitHub API to fetch plugin information and downloads.
 */
public class GitHubSourceHandler {
    
    /**
     * Functional interface for handling HTTP responses that may throw IOException
     * @param <T> Return type
     */
    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T t) throws IOException;
    }
    
    private final HttpClient httpClient;
    private final String baseUrl;
    private final Gson gson;
    private final String userAgent;
    private final String githubToken;
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
     * Constructor for GitHubSourceHandler
     * @param httpClient The HTTP client to use for requests
     * @param githubToken Optional GitHub personal access token for authenticated requests
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
    public GitHubSourceHandler(HttpClient httpClient, String githubToken, String pluginVersion, Logger logger, ExecutorService executorService, int apiTimeoutSeconds, int downloadTimeoutSeconds, String updateFolderName, int maxRetries, int baseDelayMs, int maxDelayMs) {
        this.httpClient = httpClient;
        this.baseUrl = "https://api.github.com";
        this.gson = new Gson();
        this.userAgent = "ByteMe6/hanafubuki-plugin-updater/" + pluginVersion;
        this.githubToken = githubToken;
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
    public GitHubSourceHandler(HttpClient httpClient, String githubToken, String pluginVersion, Logger logger, ExecutorService executorService, int apiTimeoutSeconds, int downloadTimeoutSeconds, String updateFolderName) {
        this(httpClient, githubToken, pluginVersion, logger, executorService, apiTimeoutSeconds, downloadTimeoutSeconds, updateFolderName, 3, 1000, 10000);
    }
    
    /**
     * Execute an HTTP request with exponential backoff retry logic
     * @param requestBuilder Function that creates the HttpRequest
     * @param responseHandler Function that handles the HttpResponse
     * @param operationName Name of the operation for logging
     * @return Response body or null on failure
     */
    private <T> T executeWithRetry(HttpRequest.Builder requestBuilder, ThrowingFunction<HttpResponse<String>, T> responseHandler, String operationName) throws IOException {
        HttpRequest request = requestBuilder.build();
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();
                
                // Check if we should retry
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
                
                // Success or non-retryable error
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
    
    /**
     * Execute an HTTP request with exponential backoff retry logic for file downloads
     * @param requestBuilder Function that creates the HttpRequest
     * @param targetPath Path to save the downloaded file
     * @param operationName Name of the operation for logging
     */
    private void executeDownloadWithRetry(HttpRequest.Builder requestBuilder, Path targetPath, String operationName) throws IOException {
        HttpRequest request = requestBuilder.build();
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(targetPath));
                int statusCode = response.statusCode();
                
                // Check if we should retry
                if (shouldRetry(statusCode)) {
                    if (attempt < maxRetries) {
                        long delay = calculateDelay(attempt);
                        logger.warning(String.format("Retrying %s (attempt %d/%d) after %dms - HTTP %d", 
                                operationName, attempt + 1, maxRetries, delay, statusCode));
                        Thread.sleep(delay);
                        // Clean up partial download
                        Files.deleteIfExists(targetPath);
                        continue;
                    } else {
                        Files.deleteIfExists(targetPath);
                        throw new IOException(String.format("Max retries exceeded for %s: HTTP %d", 
                                operationName, statusCode));
                    }
                }
                
                // Success or non-retryable error
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
    
    /**
     * Determine if a status code should trigger a retry
     * Retries on: 429 (rate limit), 5xx (server errors)
     */
    private boolean shouldRetry(int statusCode) {
        return statusCode == 429 || (statusCode >= 500 && statusCode < 600);
    }
    
    /**
     * Calculate delay with exponential backoff and jitter
     * delay = min(baseDelay * 2^attempt + jitter, maxDelay)
     */
    private long calculateDelay(int attempt) {
        long exponentialDelay = baseDelayMs * (1L << attempt);
        long jitter = random.nextInt((int) Math.min(baseDelayMs, 1000));
        return Math.min(exponentialDelay + jitter, maxDelayMs);
    }
    
    /**
     * Get the latest version of a plugin from GitHub
     * This method retrieves the latest release for a GitHub repository
     * @param pluginName Name of the plugin (format: owner/repo)
     * @param pluginDetails Plugin configuration details (must contain repo)
     * @return Latest version string or null if not found
     * @throws IOException If there's an error during API request
     */
    public String getLatestVersion(String pluginName, Map<String, Object> pluginDetails) throws IOException {
        try {
            // For GitHub, we need to know the owner and repo name
            String repo = (String) pluginDetails.get("repo");
            if (repo == null || repo.isEmpty()) {
                throw new IOException("GitHub repo not configured for plugin: " + pluginName);
            }
            
            String[] parts = repo.split("/");
            if (parts.length != 2) {
                throw new IOException("Invalid GitHub repository format for plugin: " + pluginName + " (expected owner/repo)");
            }
            
            String owner = parts[0];
            String repoName = parts[1];
            
            // Get the latest release
            String releasesUrl = baseUrl + "/repos/" + owner + "/" + repoName + "/releases/latest";
            HttpRequest.Builder requestBuilder = buildRequestBuilder(releasesUrl, false);
            
            String responseBody = executeWithRetry(requestBuilder, response -> {
                if (response.statusCode() == 200) {
                    JsonObject releaseJson = gson.fromJson(response.body(), JsonObject.class);
                    return releaseJson.get("tag_name").getAsString();
                } else if (response.statusCode() == 404) {
                    // If no releases exist, try getting the default branch version
                    String defaultBranch = (String) pluginDetails.getOrDefault("default_branch", "main");
                    String branchUrl = baseUrl + "/repos/" + owner + "/" + repoName + "/branches/" + defaultBranch;
                    HttpRequest.Builder branchRequestBuilder = buildRequestBuilder(branchUrl, false);
                    
                    String branchResponseBody = executeWithRetry(branchRequestBuilder, branchResponse -> {
                        if (branchResponse.statusCode() == 200) {
                            JsonObject branchJson = gson.fromJson(branchResponse.body(), JsonObject.class);
                            return branchJson.getAsJsonObject("commit").get("sha").getAsString().substring(0, 7);
                        } else {
                            throw new IOException("HTTP " + branchResponse.statusCode() + ": " + branchResponse.body());
                        }
                    }, "getLatestVersion (branch fallback) for " + pluginName);
                    
                    return branchResponseBody;
                } else {
                    throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
                }
            }, "getLatestVersion for " + pluginName);
            
            return responseBody;
            
        } catch (Exception e) {
            throw new IOException("Failed to get latest version from GitHub: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get the download URL for a specific version of a plugin
     * @param pluginName Name of the plugin
     * @param version Version number
     * @param pluginDetails Plugin configuration details (must contain repo)
     * @return Download URL string or null if not found
     * @throws IOException If there's an an error during API request
     */
    public String getDownloadUrl(String pluginName, String version, Map<String, Object> pluginDetails) throws IOException {
        try {
            String repo = (String) pluginDetails.get("repo");
            if (repo == null || repo.isEmpty()) {
                throw new IOException("GitHub repo not configured for plugin: " + pluginName);
            }
            
            String[] parts = repo.split("/");
            if (parts.length != 2) {
                throw new IOException("Invalid GitHub repository format for plugin: " + pluginName + " (expected owner/repo)");
            }
            
            String owner = parts[0];
            String repoName = parts[1];
            
            // Get the release for this version
            String releasesUrl = baseUrl + "/repos/" + owner + "/" + repoName + "/releases/tags/" + version;
            HttpRequest.Builder requestBuilder = buildRequestBuilder(releasesUrl, false);
            
            String responseBody = executeWithRetry(requestBuilder, response -> {
                if (response.statusCode() == 200) {
                    JsonObject releaseJson = gson.fromJson(response.body(), JsonObject.class);
                    
                    // Get the asset matching pattern or first asset
                    String assetPattern = (String) pluginDetails.get("asset_pattern");
                    JsonArray assets = releaseJson.getAsJsonArray("assets");
                    if (assets != null && !assets.isEmpty()) {
                        if (assetPattern != null && !assetPattern.isEmpty()) {
                            // Match asset by pattern
                            for (JsonElement assetElement : assets) {
                                JsonObject asset = assetElement.getAsJsonObject();
                                String assetName = asset.get("name").getAsString();
                                if (matchesPattern(assetName, assetPattern)) {
                                    return asset.get("browser_download_url").getAsString();
                                }
                            }
                        }
                        // Fallback to first asset
                        JsonObject asset = assets.get(0).getAsJsonObject();
                        return asset.get("browser_download_url").getAsString();
                    }
                    throw new IOException("No assets found in release for " + pluginName + " version " + version);
                } else if (response.statusCode() == 404) {
                    // If no specific release exists, try getting the latest release
                    String latestReleaseUrl = baseUrl + "/repos/" + owner + "/" + repoName + "/releases/latest";
                    HttpRequest.Builder latestRequestBuilder = buildRequestBuilder(latestReleaseUrl, false);
                    
                    String latestResponseBody = executeWithRetry(latestRequestBuilder, latestResponse -> {
                        if (latestResponse.statusCode() == 200) {
                            JsonObject releaseJson = gson.fromJson(latestResponse.body(), JsonObject.class);
                            JsonArray assets = releaseJson.getAsJsonArray("assets");
                            if (assets != null && !assets.isEmpty()) {
                                String assetPattern = (String) pluginDetails.get("asset_pattern");
                                if (assetPattern != null && !assetPattern.isEmpty()) {
                                    for (JsonElement assetElement : assets) {
                                        JsonObject asset = assetElement.getAsJsonObject();
                                        String assetName = asset.get("name").getAsString();
                                        if (matchesPattern(assetName, assetPattern)) {
                                            return asset.get("browser_download_url").getAsString();
                                        }
                                    }
                                }
                                JsonObject asset = assets.get(0).getAsJsonObject();
                                return asset.get("browser_download_url").getAsString();
                            }
                            throw new IOException("No assets found in latest release for " + pluginName);
                        } else {
                            throw new IOException("HTTP " + latestResponse.statusCode() + ": " + latestResponse.body());
                        }
                    }, "getDownloadUrl (latest fallback) for " + pluginName);
                    
                    return latestResponseBody;
                } else {
                    throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
                }
            }, "getDownloadUrl for " + pluginName);
            
            return responseBody;
            
        } catch (Exception e) {
            throw new IOException("Failed to get download URL from GitHub: " + e.getMessage(), e);
        }
    }
    
    /**
     * Download and install a plugin from GitHub
     * Downloads to plugins/{update-folder}/<same filename>.part (from bukkit.yml settings.update-folder),
     * verifies plugin.yml name, then atomic move
     * @param pluginName Name of the plugin
     * @param downloadUrl URL to download the plugin from
     * @param installedJarPath Path to the currently installed JAR file
     * @param pluginDetails Plugin configuration details
     * @throws IOException If there's an error during download/installation or verification fails
     */
    public void downloadAndInstall(String pluginName, String downloadUrl, Path installedJarPath, Map<String, Object> pluginDetails) throws IOException {
        try {
            // Determine target filename (same as installed jar)
            String targetFileName = installedJarPath.getFileName().toString();
            Path updateDir = installedJarPath.getParent().resolve(updateFolderName);
            Files.createDirectories(updateDir);
            
            Path tempFile = updateDir.resolve(targetFileName + ".part");
            Path targetFile = updateDir.resolve(targetFileName);
            
            // Download the file
            logger.info("Downloading " + pluginName + " from: " + downloadUrl);
            
            HttpRequest.Builder requestBuilder = buildRequestBuilder(downloadUrl, true);
            
            executeDownloadWithRetry(requestBuilder, tempFile, "downloadAndInstall for " + pluginName);
            
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
            throw new IOException("Failed to download and install plugin: " + e.getMessage(), e);
        }
    }
    
    /**
     * Build HTTP request with headers
     * @param url The URL to request
     * @param isDownload Whether this is a download request (uses download timeout)
     */
    private HttpRequest.Builder buildRequestBuilder(String url, boolean isDownload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(isDownload ? downloadTimeoutSeconds : apiTimeoutSeconds))
                .header("Accept", "application/json")
                .header("User-Agent", userAgent);
        
        if (githubToken != null && !githubToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + githubToken);
        }
        
        return builder.GET();
    }
    
    /**
     * Simple pattern matching for asset names (supports * wildcard)
     */
    private boolean matchesPattern(String assetName, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return assetName.matches(regex);
    }
}