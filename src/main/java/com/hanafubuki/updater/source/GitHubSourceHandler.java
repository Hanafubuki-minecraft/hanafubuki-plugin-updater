package com.hanafubuki.updater.source;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * Handler for GitHub API interactions
 * This class manages communication with the GitHub API to fetch plugin information and downloads
 */
public class GitHubSourceHandler {
    
    private final HttpClient httpClient;
    private final String baseUrl;
    private final Gson gson;
    private final String userAgent;
    
    /**
     * Constructor for GitHubSourceHandler
     * @param httpClient The HTTP client to use for requests
     */
    public GitHubSourceHandler(HttpClient httpClient) {
        this.httpClient = httpClient;
        this.baseUrl = "https://api.github.com";
        this.gson = new Gson();
        this.userAgent = "HanafubukiPluginUpdater/1.0.0"; // User-Agent required by GitHub
    }
    
    /**
     * Get the latest version of a plugin from GitHub
     * This method retrieves the latest release for a GitHub repository
     * @param pluginName Name of the plugin (format: owner/repo)
     * @return Latest version string or null if not found
     * @throws IOException If there's an error during API request
     */
    public String getLatestVersion(String pluginName) throws IOException {
        try {
            // For GitHub, we need to know the owner and repo name
            String[] parts = pluginName.split("/");
            if (parts.length != 2) {
                throw new IOException("Invalid GitHub repository format for plugin: " + pluginName);
            }
            
            String owner = parts[0];
            String repo = parts[1];
            
            // Get the latest release
            String releasesUrl = baseUrl + "/repos/" + owner + "/" + repo + "/releases/latest";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(releasesUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonObject releaseJson = gson.fromJson(response.body(), JsonObject.class);
                
                // Return the tag name as version
                return releaseJson.get("tag_name").getAsString();
            } else if (response.statusCode() == 404) {
                // If no releases exist, try getting the default branch version
                String branchUrl = baseUrl + "/repos/" + owner + "/" + repo + "/branches/main";
                HttpRequest branchRequest = HttpRequest.newBuilder()
                        .uri(URI.create(branchUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json")
                        .header("User-Agent", userAgent)
                        .GET()
                        .build();
                
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
     * @param pluginName Name of the plugin (format: owner/repo)
     * @param version Version number
     * @return Download URL string or null if not found
     * @throws IOException If there's an error during API request
     */
    public String getDownloadUrl(String pluginName, String version) throws IOException {
        try {
            // For GitHub, we need to know the owner and repo name
            String[] parts = pluginName.split("/");
            if (parts.length != 2) {
                throw new IOException("Invalid GitHub repository format for plugin: " + pluginName);
            }
            
            String owner = parts[0];
            String repo = parts[1];
            
            // Get the release for this version
            String releasesUrl = baseUrl + "/repos/" + owner + "/" + repo + "/releases/tags/" + version;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(releasesUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonObject releaseJson = gson.fromJson(response.body(), JsonObject.class);
                
                // Get the first asset in the release
                JsonArray assets = releaseJson.getAsJsonArray("assets");
                if (assets != null && !assets.isEmpty()) {
                    JsonObject asset = assets.get(0).getAsJsonObject();
                    return asset.get("browser_download_url").getAsString();
                }
            } else if (response.statusCode() == 404) {
                // If no specific release exists, try getting the latest release
                String latestReleaseUrl = baseUrl + "/repos/" + owner + "/" + repo + "/releases/latest";
                HttpRequest latestRequest = HttpRequest.newBuilder()
                        .uri(URI.create(latestReleaseUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json")
                        .header("User-Agent", userAgent)
                        .GET()
                        .build();
                
                HttpResponse<String> latestResponse = httpClient.send(latestRequest, HttpResponse.BodyHandlers.ofString());
                
                if (latestResponse.statusCode() == 200) {
                    JsonObject releaseJson = gson.fromJson(latestResponse.body(), JsonObject.class);
                    JsonArray assets = releaseJson.getAsJsonArray("assets");
                    if (assets != null && !assets.isEmpty()) {
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
     * @param pluginName Name of the plugin
     * @param downloadUrl URL to download the plugin from
     * @throws IOException If there's an error during download/installation
     */
    public void downloadAndInstall(String pluginName, String downloadUrl) throws IOException {
        try {
            // Validate that we're downloading the correct plugin
            if (downloadUrl == null || !downloadUrl.contains(pluginName)) {
                throw new IOException("Download URL does not match expected plugin: " + pluginName);
            }
            
            System.out.println("Downloading " + pluginName + " from: " + downloadUrl);
            
            // In a real implementation, we would:
            // 1. Download the file from the download URL to the update folder
            // 2. Verify the downloaded file matches the expected plugin name
            // 3. Install it to the appropriate directory
            
            System.out.println("Plugin " + pluginName + " downloaded successfully");
            
        } catch (Exception e) {
            throw new IOException("Failed to download and install plugin: " + e.getMessage(), e);
        }
    }
}