package com.hanafubuki.updater.manager;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

import com.hanafubuki.updater.source.ModrinthSourceHandler;
import com.hanafubuki.updater.source.GitHubSourceHandler;
import com.hanafubuki.updater.util.VersionUtils;

/**
 * Manager class that handles updates from different repository sources
 */
public class RepositoryManager {
    
    private final HttpClient httpClient;
    private final ModrinthSourceHandler modrinthHandler;
    private final GitHubSourceHandler gitHubHandler;
    private final JavaPlugin plugin;
    
    public RepositoryManager() {
        this.plugin = com.hanafubuki.updater.PluginUpdater.getPlugin(com.hanafubuki.updater.PluginUpdater.class);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        
        this.modrinthHandler = new ModrinthSourceHandler(httpClient);
        this.gitHubHandler = new GitHubSourceHandler(httpClient);
    }
    
    /**
     * Update a plugin from Modrinth asynchronously
     * @param pluginName Name of the plugin to update
     * @param pluginDetails Details about the plugin
     */
    public void updateFromModrinth(String pluginName, Map<String, Object> pluginDetails) {
        CompletableFuture.runAsync(() -> {
            try {
                // Get current version
                String currentVersion = (String) pluginDetails.get("version");
                
                // Check for updates
                String latestVersion = modrinthHandler.getLatestVersion(pluginName);
                
                if (VersionUtils.isVersionNewer(latestVersion, currentVersion)) {
                    System.out.println("New version available: " + latestVersion);
                    
                    // Download and install update
                    String downloadUrl = modrinthHandler.getDownloadUrl(pluginName, latestVersion);
                    modrinthHandler.downloadAndInstall(pluginName, downloadUrl);
                    
                    System.out.println("Successfully updated " + pluginName + " to version " + latestVersion);
                    
                    // Notify ops about the update
                    notifyOps(pluginName, latestVersion);
                } else {
                    System.out.println("Plugin " + pluginName + " is up to date");
                }
            } catch (Exception e) {
                System.err.println("Failed to update from Modrinth: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    /**
     * Update a plugin from GitHub asynchronously
     * @param pluginName Name of the plugin to update
     * @param pluginDetails Details about the plugin
     */
    public void updateFromGitHub(String pluginName, Map<String, Object> pluginDetails) {
        CompletableFuture.runAsync(() -> {
            try {
                // Get current version
                String currentVersion = (String) pluginDetails.get("version");
                
                // Check for updates
                String latestVersion = gitHubHandler.getLatestVersion(pluginName);
                
                if (VersionUtils.isVersionNewer(latestVersion, currentVersion)) {
                    System.out.println("New version available: " + latestVersion);
                    
                    // Download and install update
                    String downloadUrl = gitHubHandler.getDownloadUrl(pluginName, latestVersion);
                    gitHubHandler.downloadAndInstall(pluginName, downloadUrl);
                    
                    System.out.println("Successfully updated " + pluginName + " to version " + latestVersion);
                    
                    // Notify ops about the update
                    notifyOps(pluginName, latestVersion);
                } else {
                    System.out.println("Plugin " + pluginName + " is up to date");
                }
            } catch (Exception e) {
                System.err.println("Failed to update from GitHub: " + e.getMessage());
                e.printStackTrace();
            }
        });
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