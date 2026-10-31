package com.hanafubuki.updater.manager;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.hanafubuki.updater.source.ModrinthSourceHandler;
import com.hanafubuki.updater.source.GitHubSourceHandler;
import com.hanafubuki.updater.util.VersionUtils;

import java.util.logging.Logger;

/**
 * Manager class that handles updates from different repository sources
 */
public class RepositoryManager {
    
    private final HttpClient httpClient;
    private final ModrinthSourceHandler modrinthHandler;
    private final GitHubSourceHandler gitHubHandler;
    private final JavaPlugin plugin;
    private final String serverVersion;
    private final ExecutorService executorService;
    
    public RepositoryManager(ExecutorService executorService) {
        this.executorService = executorService;
        this.plugin = com.hanafubuki.updater.PluginUpdater.getPlugin(com.hanafubuki.updater.PluginUpdater.class);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        
        // Get plugin version
        String pluginVersion = plugin.getPluginMeta().getVersion();
        
        // Get server version from Bukkit
        String bukkitVersion = Bukkit.getBukkitVersion();
        // Extract version like "1.21.9" from "git-Bukkit-1.21.9-R0.1-SNAPSHOT"
        String extractedVersion = bukkitVersion;
        if (bukkitVersion.contains("-")) {
            String[] parts = bukkitVersion.split("-");
            for (String part : parts) {
                if (part.matches("\\d+\\.\\d+\\.\\d+.*")) {
                    extractedVersion = part;
                    break;
                }
            }
        }
        this.serverVersion = extractedVersion;
        
        // Get plugin logger
        Logger logger = plugin.getLogger();
        
        this.modrinthHandler = new ModrinthSourceHandler(httpClient, this.serverVersion, pluginVersion, logger, executorService);
        
        // Get GitHub token from config if available
        String githubToken = null;
        
        this.gitHubHandler = new GitHubSourceHandler(httpClient, githubToken, pluginVersion, logger, executorService);
    }
    
    /**
     * Update a plugin from Modrinth asynchronously
     * @param pluginName Name of the plugin to update
     * @param pluginDetails Details about the plugin
     * @return CompletableFuture that completes when the update is done
     */
    public CompletableFuture<?> updateFromModrinth(String pluginName, Map<String, Object> pluginDetails) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Find the installed JAR file
                Path installedJarPath = findInstalledJar(pluginName);
                if (installedJarPath == null) {
                    plugin.getLogger().warning("Could not find installed JAR for plugin: " + pluginName);
                    return;
                }
                
                // Get current version
                String currentVersion = (String) pluginDetails.get("version");
                
                // Check for updates
                String latestVersion = modrinthHandler.getLatestVersion(pluginName, pluginDetails, installedJarPath);
                
                if (latestVersion == null) {
                    plugin.getLogger().info("No compatible version found for " + pluginName + " on Modrinth");
                    return;
                }
                
                if (VersionUtils.isVersionNewer(latestVersion, currentVersion)) {
                    plugin.getLogger().info("New version available for " + pluginName + ": " + latestVersion);
                    
                    // Get download URL
                    String downloadUrl = modrinthHandler.getDownloadUrl(pluginName, latestVersion, pluginDetails, installedJarPath);
                    if (downloadUrl == null) {
                        plugin.getLogger().warning("Could not get download URL for " + pluginName + " version " + latestVersion);
                        return;
                    }
                    
                    // Get expected SHA-512 for verification
                    String expectedSha512 = modrinthHandler.getExpectedSha512(pluginName, latestVersion, pluginDetails, installedJarPath);
                    if (expectedSha512 == null) {
                        plugin.getLogger().warning("Could not get expected SHA-512 for " + pluginName + " version " + latestVersion);
                        return;
                    }
                    
                    // Download and install update
                    modrinthHandler.downloadAndInstall(pluginName, downloadUrl, installedJarPath, expectedSha512);
                    
                    plugin.getLogger().info("Successfully updated " + pluginName + " to version " + latestVersion);
                    
                    // Notify ops about the update
                    notifyOps(pluginName, latestVersion);
                } else {
                    plugin.getLogger().info("Plugin " + pluginName + " is up to date (current: " + currentVersion + ")");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to update " + pluginName + " from Modrinth: " + e.getMessage());
                e.printStackTrace();
            }
        }, executorService);
    }
    
    /**
     * Update a plugin from GitHub asynchronously
     * @param pluginName Name of the plugin to update
     * @param pluginDetails Details about the plugin
     * @return CompletableFuture that completes when the update is done
     */
    public CompletableFuture<?> updateFromGitHub(String pluginName, Map<String, Object> pluginDetails) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Find the installed JAR file
                Path installedJarPath = findInstalledJar(pluginName);
                if (installedJarPath == null) {
                    plugin.getLogger().warning("Could not find installed JAR for plugin: " + pluginName);
                    return;
                }
                
                // Get current version
                String currentVersion = (String) pluginDetails.get("version");
                
                // Check for updates
                String latestVersion = gitHubHandler.getLatestVersion(pluginName, pluginDetails);
                
                if (latestVersion == null) {
                    plugin.getLogger().info("No version found for " + pluginName + " on GitHub");
                    return;
                }
                
                if (VersionUtils.isVersionNewer(latestVersion, currentVersion)) {
                    plugin.getLogger().info("New version available for " + pluginName + ": " + latestVersion);
                    
                    // Get download URL
                    String downloadUrl = gitHubHandler.getDownloadUrl(pluginName, latestVersion, pluginDetails);
                    if (downloadUrl == null) {
                        plugin.getLogger().warning("Could not get download URL for " + pluginName + " version " + latestVersion);
                        return;
                    }
                    
                    // Download and install update
                    gitHubHandler.downloadAndInstall(pluginName, downloadUrl, installedJarPath, pluginDetails);
                    
                    plugin.getLogger().info("Successfully updated " + pluginName + " to version " + latestVersion);
                    
                    // Notify ops about the update
                    notifyOps(pluginName, latestVersion);
                } else {
                    plugin.getLogger().info("Plugin " + pluginName + " is up to date (current: " + currentVersion + ")");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to update " + pluginName + " from GitHub: " + e.getMessage());
                e.printStackTrace();
            }
        }, executorService);
    }
    
    /**
     * Find the installed JAR file for a plugin
     * @param pluginName Name of the plugin
     * @return Path to the JAR file or null if not found
     */
    private Path findInstalledJar(String pluginName) {
        // Get plugins directory from plugin's data folder parent
        File pluginsDir = plugin.getDataFolder().getParentFile();
        if (pluginsDir == null || !pluginsDir.exists() || !pluginsDir.isDirectory()) {
            return null;
        }
        
        // Look for JAR file matching plugin name
        File[] jars = pluginsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null) {
            return null;
        }
        
        for (File jar : jars) {
            // Check if JAR contains plugin.yml with matching name
            try (var jarFile = new java.util.jar.JarFile(jar)) {
                var entry = jarFile.getEntry("plugin.yml");
                if (entry != null) {
                    try (var is = jarFile.getInputStream(entry);
                         var reader = new java.io.InputStreamReader(is)) {
                        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
                        String name = yaml.getString("name");
                        if (pluginName.equalsIgnoreCase(name)) {
                            return jar.toPath();
                        }
                    }
                }
            } catch (Exception ignored) {
                // Continue checking other JARs
            }
        }
        
        return null;
    }
    
    /**
     * Notify ops about a plugin update
     * @param pluginName Name of the updated plugin
     * @param version New version of the plugin
     */
    private void notifyOps(String pluginName, String version) {
        String message = ChatColor.GREEN + "Plugin " + pluginName + " updated to version " + version + 
                        ChatColor.WHITE + ", try restarting";
        
        Bukkit.broadcastMessage(message);
    }
}