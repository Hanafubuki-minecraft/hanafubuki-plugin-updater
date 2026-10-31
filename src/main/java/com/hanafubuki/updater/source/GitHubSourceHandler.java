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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

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
    
    private final HttpClient httpClient;
    private final String baseUrl;
    private final Gson gson;
    private final String userAgent;
    private final String githubToken;
    private final Logger logger;
    private final ExecutorService executorService;
    
    /**
     * Constructor for GitHubSourceHandler
     * @param httpClient The HTTP client to use for requests
     * @param githubToken Optional GitHub personal access token for authenticated requests
     * @param pluginVersion The version of the updater plugin
     * @param logger The logger to use for output
     * @param executorService The executor service to use for async operations
     */
    public GitHubSourceHandler(HttpClient httpClient, String githubToken, String pluginVersion, Logger logger, ExecutorService executorService) {
        this.httpClient = httpClient;
        this.baseUrl = "https://api.github.com";
        this.gson = new Gson();
        this.userAgent = "ByteMe6/hanafubuki-plugin-updater/" + pluginVersion;
        this.githubToken = githubToken;
        this.logger = logger;
        this.executorService = executorService;
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
            HttpRequest request = buildRequest(releasesUrl);
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonObject releaseJson = gson.fromJson(response.body(), JsonObject.class);
                
                // Return the tag name as version
                return releaseJson.get("tag_name").getAsString();
            } else if (response.statusCode() == 404) {
                // If no releases exist, try getting the default branch version
                String defaultBranch = (String) pluginDetails.getOrDefault("default_branch", "main");
                String branchUrl = baseUrl + "/repos/" + owner + "/" + repoName + "/branches/" + defaultBranch;
                HttpRequest branchRequest = buildRequest(branchUrl);
                
                HttpResponse<String> branchResponse = httpClient.send(branchRequest, HttpResponse.BodyHandlers.ofString());
                
                if (branchResponse.statusCode() == 200) {
                    JsonObject branchJson = gson.fromJson(branchResponse.body(), JsonObject.class);
                    // Return the commit hash as version
                    return branchJson.getAsJsonObject("commit").get("sha").getAsString().substring(0, 7);
                }
            } else {
                throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
            }
            
        } catch (Exception e) {
            throw new IOException("Failed to get latest version from GitHub: " + e.getMessage(), e);
        }
        
        return null;
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
            HttpRequest request = buildRequest(releasesUrl);
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
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
            } else if (response.statusCode() == 404) {
                // If no specific release exists, try getting the latest release
                String latestReleaseUrl = baseUrl + "/repos/" + owner + "/" + repoName + "/releases/latest";
                HttpRequest latestRequest = buildRequest(latestReleaseUrl);
                
                HttpResponse<String> latestResponse = httpClient.send(latestRequest, HttpResponse.BodyHandlers.ofString());
                
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
                }
            } else {
                throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
            }
            
        } catch (Exception e) {
            throw new IOException("Failed to get download URL from GitHub: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Download and install a plugin from GitHub
     * Downloads to plugins/update/<same filename>.part, verifies plugin.yml name, then atomic move
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
            Path updateDir = installedJarPath.getParent().resolve("update");
            Files.createDirectories(updateDir);
            
            Path tempFile = updateDir.resolve(targetFileName + ".part");
            Path targetFile = updateDir.resolve(targetFileName);
            
            // Download the file
            logger.info("Downloading " + pluginName + " from: " + downloadUrl);
            
            HttpRequest request = buildRequest(downloadUrl);
            
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            
            if (response.statusCode() != 200) {
                Files.deleteIfExists(tempFile);
                throw new IOException("HTTP " + response.statusCode() + " downloading " + pluginName);
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
            throw new IOException("Failed to download and install plugin: " + e.getMessage(), e);
        }
    }
    
    /**
     * Build HTTP request with headers
     */
    private HttpRequest buildRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", userAgent);
        
        if (githubToken != null && !githubToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + githubToken);
        }
        
        return builder.GET().build();
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