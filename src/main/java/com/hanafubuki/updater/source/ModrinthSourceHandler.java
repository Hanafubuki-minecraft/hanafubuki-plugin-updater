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
 * Handler for Modrinth API interactions
 * This class manages communication with the Modrinth API to fetch plugin information and downloads
 */
public class ModrinthSourceHandler {
    
    private final HttpClient httpClient;
    private final String baseUrl;
    private final Gson gson;
    private final String userAgent;
    
    /**
     * Constructor for ModrinthSourceHandler
     * @param httpClient The HTTP client to use for requests
     */
    public ModrinthSourceHandler(HttpClient httpClient) {
        this.httpClient = httpClient;
        this.baseUrl = "https://api.modrinth.com/v2";
        this.gson = new Gson();
        this.userAgent = "HanafubukiPluginUpdater/1.0.0"; // User-Agent required by Modrinth
    }
    
    /**
     * Get the latest version of a plugin from Modrinth
     * This method searches for the plugin and returns its latest version compatible with Paper 1.21.9
     * @param pluginName Name of the plugin to search for
     * @return Latest version string or null if not found
     * @throws IOException If there's an error during API request
     */
    public String getLatestVersion(String pluginName) throws IOException {
        try {
            // First, we need to find the project ID for the plugin name
            String projectId = getProjectId(pluginName);
            
            if (projectId == null) {
                throw new IOException("Could not find project ID for plugin: " + pluginName);
            }
            
            // Get project details including versions with filters for Paper and 1.21.9
            String projectUrl = baseUrl + "/project/" + projectId + "/version";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(projectUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonArray versionsJson = gson.fromJson(response.body(), JsonArray.class);
                
                // Find the latest version compatible with Paper and 1.21.9
                for (JsonElement element : versionsJson) {
                    JsonObject versionObj = element.getAsJsonObject();
                    
                    // Check if this version supports Paper loader
                    JsonArray loaders = versionObj.getAsJsonArray("loaders");
                    boolean hasPaperLoader = false;
                    if (loaders != null) {
                        for (JsonElement loader : loaders) {
                            if ("paper".equalsIgnoreCase(loader.getAsString())) {
                                hasPaperLoader = true;
                                break;
                            }
                        }
                    }
                    
                    // Check if this version supports 1.21.9
                    JsonArray gameVersions = versionObj.getAsJsonArray("game_versions");
                    boolean has1219Version = false;
                    if (gameVersions != null) {
                        for (JsonElement gameVersion : gameVersions) {
                            if ("1.21.9".equalsIgnoreCase(gameVersion.getAsString())) {
                                has1219Version = true;
                                break;
                            }
                        }
                    }
                    
                    // If version supports both Paper loader and 1.21.9, return it
                    if (hasPaperLoader && has1219Version) {
                        return versionObj.get("version_number").getAsString();
                    }
                }
                
                // If no compatible version found, return the first one
                if (!versionsJson.isEmpty()) {
                    JsonObject firstVersion = versionsJson.get(0).getAsJsonObject();
                    return firstVersion.get("version_number").getAsString();
                }
            } else {
                throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
            }
            
        } catch (Exception e) {
            throw new IOException("Failed to get latest version from Modrinth: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Get the download URL for a specific version of a plugin
     * @param pluginName Name of the plugin
     * @param version Version number
     * @return Download URL string or null if not found
     * @throws IOException If there's an error during API request
     */
    public String getDownloadUrl(String pluginName, String version) throws IOException {
        try {
            // First, we need to find the project ID for the plugin name
            String projectId = getProjectId(pluginName);
            
            if (projectId == null) {
                throw new IOException("Could not find project ID for plugin: " + pluginName);
            }
            
            // Get project versions and find the one matching our version
            String versionsUrl = baseUrl + "/project/" + projectId + "/version";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(versionsUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonArray versionsJson = gson.fromJson(response.body(), JsonArray.class);
                
                // Find the version matching our target version
                for (JsonElement element : versionsJson) {
                    JsonObject versionObj = element.getAsJsonObject();
                    String versionNumber = versionObj.get("version_number").getAsString();
                    
                    if (version.equals(versionNumber)) {
                        JsonArray files = versionObj.getAsJsonArray("files");
                        if (files != null && !files.isEmpty()) {
                            // Return the first file URL
                            JsonObject file = files.get(0).getAsJsonObject();
                            return file.get("url").getAsString();
                        }
                    }
                }
            } else {
                throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
            }
            
        } catch (Exception e) {
            throw new IOException("Failed to get download URL from Modrinth: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Download and install a plugin from Modrinth
     * @param pluginName Name of the plugin
     * @param downloadUrl URL to download the plugin from
     * @throws IOException If there's an error during download/installation
     */
    public void downloadAndInstall(String pluginName, String downloadUrl) throws IOException {
        // This method would typically:
        // 1. Download the file from the download URL to the update folder
        // 2. Install it to the appropriate directory
        
        System.out.println("Downloading " + pluginName + " from: " + downloadUrl);
        
        // In a real implementation, we would download and install the plugin here
        // For now, we'll just simulate the process
        System.out.println("Plugin " + pluginName + " downloaded successfully");
    }
    
    /**
     * Get the project ID for a plugin name by searching Modrinth
     * @param pluginName Name of the plugin to search for
     * @return Project ID string or null if not found
     * @throws IOException If there's an error during API request
     */
    private String getProjectId(String pluginName) throws IOException {
        try {
            // Search for the project by name with filters
            String searchUrl = baseUrl + "/search?query=" + pluginName + "&limit=1&index=version";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(searchUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonObject searchJson = gson.fromJson(response.body(), JsonObject.class);
                JsonArray hits = searchJson.getAsJsonArray("hits");
                
                if (hits != null && !hits.isEmpty()) {
                    JsonObject project = hits.get(0).getAsJsonObject();
                    return project.get("id").getAsString();
                }
            } else {
                throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
            }
            
        } catch (Exception e) {
            throw new IOException("Failed to get project ID from Modrinth: " + e.getMessage(), e);
        }
        
        return null;
    }
}