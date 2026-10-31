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
 * Handler for Modrinth API interactions
 * This class manages communication with the Modrinth API to fetch plugin information and downloads.
 * Uses SHA-512 hash of installed JAR for secure identification (no search by name).
 */
public class ModrinthSourceHandler {
    
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
    
    /**
     * Constructor for ModrinthSourceHandler
     * @param httpClient The HTTP client to use for requests
     * @param serverVersion The server version to filter for (e.g., "1.21.9")
     * @param pluginVersion The version of the updater plugin
     * @param logger The logger to use for output
     * @param executorService The executor service to use for async operations
     * @param apiTimeoutSeconds API request timeout in seconds
     * @param downloadTimeoutSeconds Download timeout in seconds
     * @param updateFolderName The name of the update folder (from bukkit.yml settings.update-folder)
     */
    public ModrinthSourceHandler(HttpClient httpClient, String serverVersion, String pluginVersion, Logger logger, ExecutorService executorService, int apiTimeoutSeconds, int downloadTimeoutSeconds, String updateFolderName) {
        this.httpClient = httpClient;
        this.baseUrl = "https://api.modrinth.com/v2";
        this.gson = new Gson();
        this.userAgent = "ByteMe6/hanafubuki-plugin-updater/" + pluginVersion;
        this.serverVersion = serverVersion;
        this.logger = logger;
        this.executorService = executorService;
        this.apiTimeoutSeconds = apiTimeoutSeconds;
        this.downloadTimeoutSeconds = downloadTimeoutSeconds;
        this.updateFolderName = updateFolderName != null ? updateFolderName : "update";
    }
    
    /**
     * Build the versions API URL with proper URL encoding for query parameters.
     * Modrinth API expects JSON arrays in query params, but square brackets must be URL-encoded.
     */
    private String buildVersionsUrl(String projectId) {
        String loadersParam = URLEncoder.encode("[\"paper\"]", StandardCharsets.UTF_8);
        String gameVersionsParam = URLEncoder.encode("[\"", StandardCharsets.UTF_8) 
                + URLEncoder.encode(serverVersion, StandardCharsets.UTF_8) 
                + URLEncoder.encode("\"]", StandardCharsets.UTF_8);
        return baseUrl + "/project/" + projectId + "/version?loaders=" + loadersParam + "&game_versions=" + gameVersionsParam;
    }
    
    /**
     * Get the latest version of a plugin from Modrinth
     * Uses explicit project_id from config or SHA-512 hash of installed JAR
     * @param pluginName Name of the plugin
     * @param pluginDetails Plugin configuration details (may contain project_id)
     * @param installedJarPath Path to the currently installed JAR file
     * @return Latest version string or null if not found
     * @throws IOException If there's an error during API request
     */
    public String getLatestVersion(String pluginName, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        try {
            // Get project ID from config or compute from installed JAR hash
            String projectId = getProjectId(pluginName, pluginDetails, installedJarPath);
            
            if (projectId == null) {
                throw new IOException("Could not determine project ID for plugin: " + pluginName);
            }
            
            // Get project versions with filters for Paper and server version
            // URL-encode the query parameters (square brackets must be encoded)
            String loadersParam = URLEncoder.encode("[\"paper\"]", StandardCharsets.UTF_8);
            String gameVersionsParam = URLEncoder.encode("[\"", StandardCharsets.UTF_8) + URLEncoder.encode(serverVersion, StandardCharsets.UTF_8) + URLEncoder.encode("\"]", StandardCharsets.UTF_8);
            String versionsUrl = baseUrl + "/project/" + projectId + "/version?loaders=" + loadersParam + "&game_versions=" + gameVersionsParam;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(versionsUrl))
                    .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonArray versionsJson = gson.fromJson(response.body(), JsonArray.class);
                
                // Find the latest version compatible with Paper and server version
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
                    
                    // Check if this version supports server version
                    JsonArray gameVersions = versionObj.getAsJsonArray("game_versions");
                    boolean hasServerVersion = false;
                    if (gameVersions != null) {
                        for (JsonElement gameVersion : gameVersions) {
                            if (serverVersion.equalsIgnoreCase(gameVersion.getAsString())) {
                                hasServerVersion = true;
                                break;
                            }
                        }
                    }
                    
                    // If version supports both Paper loader and server version, return it
                    if (hasPaperLoader && hasServerVersion) {
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
     * @param pluginDetails Plugin configuration details (may contain project_id)
     * @param installedJarPath Path to the currently installed JAR file
     * @return Download URL string or null if not found
     * @throws IOException If there's an error during API request
     */
    public String getDownloadUrl(String pluginName, String version, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        try {
            // Get project ID from config or compute from installed JAR hash
            String projectId = getProjectId(pluginName, pluginDetails, installedJarPath);
            
            if (projectId == null) {
                throw new IOException("Could not determine project ID for plugin: " + pluginName);
            }
            
            // Get project versions and find the one matching our version
            String versionsUrl = buildVersionsUrl(projectId);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(versionsUrl))
                    .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonArray versionsJson = gson.fromJson(response.body(), JsonArray.class);
                
                // Find the specific version
                for (JsonElement element : versionsJson) {
                    JsonObject versionObj = element.getAsJsonObject();
                    String versionNumber = versionObj.get("version_number").getAsString();
                    
                    if (version.equals(versionNumber)) {
                        // Get the files for this version - find primary file
                        JsonArray files = versionObj.getAsJsonArray("files");
                        if (files != null && !files.isEmpty()) {
                            // Look for primary file first
                            for (JsonElement fileElement : files) {
                                JsonObject file = fileElement.getAsJsonObject();
                                if (file.get("primary").getAsBoolean()) {
                                    return file.get("url").getAsString();
                                }
                            }
                            // Fallback to first file
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
     * Downloads to plugins/{update-folder}/<same filename>.part (from bukkit.yml settings.update-folder),
     * verifies hash and plugin.yml name, then atomic move
     * @param pluginName Name of the plugin
     * @param downloadUrl URL to download the plugin from
     * @param installedJarPath Path to the currently installed JAR file
     * @param expectedSha512 Expected SHA-512 hash from Modrinth API
     * @throws IOException If there's an error during download/installation or verification fails
     */
    public void downloadAndInstall(String pluginName, String downloadUrl, Path installedJarPath, String expectedSha512) throws IOException {
        try {
            // Determine target filename (same as installed jar)
            String targetFileName = installedJarPath.getFileName().toString();
            Path updateDir = installedJarPath.getParent().resolve(updateFolderName);
            Files.createDirectories(updateDir);
            
            Path tempFile = updateDir.resolve(targetFileName + ".part");
            Path targetFile = updateDir.resolve(targetFileName);
            
            // Download the file
            logger.info("Downloading " + pluginName + " from: " + downloadUrl);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .timeout(Duration.ofSeconds(downloadTimeoutSeconds))
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            
            if (response.statusCode() != 200) {
                Files.deleteIfExists(tempFile);
                throw new IOException("HTTP " + response.statusCode() + " downloading " + pluginName);
            }
            
            // Verify SHA-512 hash
            String actualSha512 = computeSha512(tempFile);
            if (!actualSha512.equalsIgnoreCase(expectedSha512)) {
                Files.deleteIfExists(tempFile);
                throw new IOException("SHA-512 mismatch for " + pluginName + ": expected " + expectedSha512 + ", got " + actualSha512);
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
            throw new IOException("Failed to download and install plugin: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get project ID by explicit config or by SHA-512 hash of installed JAR
     * @param pluginName Name of the plugin
     * @param pluginDetails Plugin configuration details (may contain project_id)
     * @param installedJarPath Path to the currently installed JAR file
     * @return Project ID or null if not found
     * @throws IOException If there's an error during API request
     */
    private String getProjectId(String pluginName, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        // First, check if project_id is explicitly configured
        if (pluginDetails.containsKey("project_id")) {
            String projectId = (String) pluginDetails.get("project_id");
            if (projectId != null && !projectId.isEmpty()) {
                return projectId;
            }
        }
        
        // Fallback: compute SHA-512 of installed JAR and lookup via version_file endpoint
        if (Files.exists(installedJarPath)) {
            String sha512 = computeSha512(installedJarPath);
            return getProjectIdFromHash(sha512);
        }
        
        return null;
    }
    
    /**
     * Get project ID from file hash using Modrinth API
     * GET /v2/version_file/{sha512}?algorithm=sha512
     * @param sha512 SHA-512 hash of the file
     * @return Project ID or null if not found
     * @throws IOException If there's an error during API request
     */
    private String getProjectIdFromHash(String sha512) throws IOException {
        try {
            String url = baseUrl + "/version_file/" + sha512 + "?algorithm=sha512";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonObject versionObj = gson.fromJson(response.body(), JsonObject.class);
                return versionObj.get("project_id").getAsString();
            } else if (response.statusCode() == 404) {
                return null; // Hash not found on Modrinth
            } else {
                throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
            }
            
        } catch (Exception e) {
            throw new IOException("Failed to get project ID from hash: " + e.getMessage(), e);
        }
    }
    
    /**
     * Compute SHA-512 hash of a file
     * @param filePath Path to the file
     * @return SHA-512 hash as hex string
     * @throws IOException If there's an error reading the file
     */
    private String computeSha512(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
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
            throw new IOException("SHA-512 algorithm not available", e);
        }
    }
    
    /**
     * Get the expected SHA-512 hash for a specific version from Modrinth
     * @param pluginName Name of the plugin
     * @param version Version number
     * @param pluginDetails Plugin configuration details
     * @param installedJarPath Path to the currently installed JAR file
     * @return Expected SHA-512 hash or null if not found
     * @throws IOException If there's an error during API request
     */
    public String getExpectedSha512(String pluginName, String version, Map<String, Object> pluginDetails, Path installedJarPath) throws IOException {
        try {
            String projectId = getProjectId(pluginName, pluginDetails, installedJarPath);
            
            if (projectId == null) {
                throw new IOException("Could not determine project ID for plugin: " + pluginName);
            }
            
            String versionsUrl = buildVersionsUrl(projectId);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(versionsUrl))
                    .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonArray versionsJson = gson.fromJson(response.body(), JsonArray.class);
                
                for (JsonElement element : versionsJson) {
                    JsonObject versionObj = element.getAsJsonObject();
                    String versionNumber = versionObj.get("version_number").getAsString();
                    
                    if (version.equals(versionNumber)) {
                        JsonArray files = versionObj.getAsJsonArray("files");
                        if (files != null && !files.isEmpty()) {
                            // Find primary file
                            for (JsonElement fileElement : files) {
                                JsonObject file = fileElement.getAsJsonObject();
                                if (file.get("primary").getAsBoolean()) {
                                    JsonObject hashes = file.getAsJsonObject("hashes");
                                    if (hashes != null && hashes.has("sha512")) {
                                        return hashes.get("sha512").getAsString();
                                    }
                                }
                            }
                            // Fallback to first file
                            JsonObject file = files.get(0).getAsJsonObject();
                            JsonObject hashes = file.getAsJsonObject("hashes");
                            if (hashes != null && hashes.has("sha512")) {
                                return hashes.get("sha512").getAsString();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to get expected SHA-512 from Modrinth: " + e.getMessage(), e);
        }
        return null;
    }
}