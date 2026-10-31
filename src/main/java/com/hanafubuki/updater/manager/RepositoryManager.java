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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.hanafubuki.updater.config.ConfigManager;
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
    private final String updateFolderName;
    private final ConfigManager configManager;
    
    // Cache for plugin name -> JAR path mapping
    private final Map<String, Path> pluginJarCache = new ConcurrentHashMap<>();
    
    // Metrics counters
    private final AtomicLong checksTotal = new AtomicLong(0);
    private final AtomicLong updatesFound = new AtomicLong(0);
    private final AtomicLong downloadsSuccess = new AtomicLong(0);
    private final AtomicLong downloadsFailed = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    
    // Dry-run mode flag
    private boolean dryRun = false;
    
    public RepositoryManager(ExecutorService executorService) {
        this.executorService = executorService;
        this.plugin = com.hanafubuki.updater.PluginUpdater.getPlugin(com.hanafubuki.updater.PluginUpdater.class);
        this.configManager = new ConfigManager();
        
        // Register listener to invalidate cache on plugin enable/disable
        Bukkit.getPluginManager().registerEvents(new PluginCacheListener(), plugin);
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
        
        // Read update folder name from bukkit.yml
        this.updateFolderName = readUpdateFolderFromBukkitYml();
        
        // Load config manager to get timeout settings
        int modrinthApiTimeout = configManager.getModrinthApiTimeout();
        int modrinthDownloadTimeout = configManager.getModrinthDownloadTimeout();
        int githubApiTimeout = configManager.getGitHubApiTimeout();
        int githubDownloadTimeout = configManager.getGitHubDownloadTimeout();
        
        // Get retry settings
        int modrinthMaxRetries = configManager.getModrinthMaxRetries();
        int modrinthBaseDelayMs = configManager.getModrinthBaseDelayMs();
        int modrinthMaxDelayMs = configManager.getModrinthMaxDelayMs();
        
        int githubMaxRetries = configManager.getGitHubMaxRetries();
        int githubBaseDelayMs = configManager.getGitHubBaseDelayMs();
        int githubMaxDelayMs = configManager.getGitHubMaxDelayMs();
        
        this.modrinthHandler = new ModrinthSourceHandler(httpClient, this.serverVersion, pluginVersion, logger, executorService, modrinthApiTimeout, modrinthDownloadTimeout, this.updateFolderName, modrinthMaxRetries, modrinthBaseDelayMs, modrinthMaxDelayMs);
        
        // Get GitHub token from config if available
        String githubToken = null;
        
        this.gitHubHandler = new GitHubSourceHandler(httpClient, githubToken, pluginVersion, logger, executorService, githubApiTimeout, githubDownloadTimeout, this.updateFolderName, githubMaxRetries, githubBaseDelayMs, githubMaxDelayMs);
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
                checksTotal.incrementAndGet();
                
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
                    updatesFound.incrementAndGet();
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
                    
                    if (dryRun) {
                        plugin.getLogger().info(String.format("[DRY-RUN] Would update %s: %s -> %s (URL: %s)", 
                                pluginName, currentVersion, latestVersion, downloadUrl));
                        return;
                    }
                    
                    // Download and install update
                    modrinthHandler.downloadAndInstall(pluginName, downloadUrl, installedJarPath, expectedSha512);
                    downloadsSuccess.incrementAndGet();
                    
                    plugin.getLogger().info("Successfully updated " + pluginName + " to version " + latestVersion);
                    
                    // Auto-update config.yml if enabled
                    if (this.configManager.getAutoUpdateConfig()) {
                        this.configManager.updatePluginVersion(pluginName, latestVersion);
                    }
                    
                    // Notify ops about the update
                    notifyOps(pluginName, latestVersion);
                } else {
                    plugin.getLogger().info("Plugin " + pluginName + " is up to date (current: " + currentVersion + ")");
                }
            } catch (Exception e) {
                errors.incrementAndGet();
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
                checksTotal.incrementAndGet();
                
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
                    updatesFound.incrementAndGet();
                    plugin.getLogger().info("New version available for " + pluginName + ": " + latestVersion);
                    
                    // Get download URL
                    String downloadUrl = gitHubHandler.getDownloadUrl(pluginName, latestVersion, pluginDetails);
                    if (downloadUrl == null) {
                        plugin.getLogger().warning("Could not get download URL for " + pluginName + " version " + latestVersion);
                        return;
                    }
                    
                    if (dryRun) {
                        plugin.getLogger().info(String.format("[DRY-RUN] Would update %s: %s -> %s (URL: %s)", 
                                pluginName, currentVersion, latestVersion, downloadUrl));
                        return;
                    }
                    
                    // Download and install update
                    gitHubHandler.downloadAndInstall(pluginName, downloadUrl, installedJarPath, pluginDetails);
                    downloadsSuccess.incrementAndGet();
                    
                    plugin.getLogger().info("Successfully updated " + pluginName + " to version " + latestVersion);
                    
                    // Auto-update config.yml if enabled
                    if (this.configManager.getAutoUpdateConfig()) {
                        this.configManager.updatePluginVersion(pluginName, latestVersion);
                    }
                    
                    // Notify ops about the update
                    notifyOps(pluginName, latestVersion);
                } else {
                    plugin.getLogger().info("Plugin " + pluginName + " is up to date (current: " + currentVersion + ")");
                }
            } catch (Exception e) {
                downloadsFailed.incrementAndGet();
                errors.incrementAndGet();
                plugin.getLogger().severe("Failed to update " + pluginName + " from GitHub: " + e.getMessage());
                e.printStackTrace();
            }
        }, executorService);
    }
    
    /**
     * Find the installed JAR file for a plugin using Bukkit's plugin registry with caching
     * @param pluginName Name of the plugin
     * @return Path to the JAR file or null if not found
     */
    private Path findInstalledJar(String pluginName) {
        // Check cache first
        Path cachedPath = pluginJarCache.get(pluginName);
        if (cachedPath != null) {
            return cachedPath;
        }
        
        // Build cache from Bukkit's loaded plugins
        buildPluginJarCache();
        
        // Check cache again after building
        return pluginJarCache.get(pluginName);
    }
    
    /**
     * Build the plugin JAR cache from Bukkit's plugin manager
     */
    private void buildPluginJarCache() {
        for (Plugin loadedPlugin : Bukkit.getPluginManager().getPlugins()) {
            String name = loadedPlugin.getName();
            File file = getPluginJarFile(loadedPlugin);
            if (file != null && file.exists()) {
                pluginJarCache.put(name, file.toPath());
            }
        }
    }
    
    /**
     * Get the JAR file for a plugin using reflection on its class
     */
    private File getPluginJarFile(Plugin plugin) {
        try {
            java.net.URL location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                return new File(location.toURI());
            }
        } catch (Exception ignored) {
        }
        return null;
    }
    
    /**
     * Listener to invalidate cache when plugins are enabled/disabled
     */
    private class PluginCacheListener implements Listener {
        @EventHandler
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin plugin = event.getPlugin();
            String name = plugin.getName();
            File file = getPluginJarFile(plugin);
            if (file != null && file.exists()) {
                pluginJarCache.put(name, file.toPath());
            }
        }
        
        @EventHandler
        public void onPluginDisable(PluginDisableEvent event) {
            pluginJarCache.remove(event.getPlugin().getName());
        }
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
    
    /**
     * Read the update folder name from bukkit.yml
     * @return The update folder name, defaulting to "update"
     */
    private String readUpdateFolderFromBukkitYml() {
        try {
            // bukkit.yml is in the server root: plugin.getDataFolder().getParentFile().getParentFile()
            File serverRoot = plugin.getDataFolder().getParentFile().getParentFile();
            if (serverRoot == null) {
                return "update";
            }
            
            File bukkitYml = new File(serverRoot, "bukkit.yml");
            if (!bukkitYml.exists()) {
                return "update";
            }
            
            var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(bukkitYml);
            String updateFolder = yaml.getString("settings.update-folder");
            
            if (updateFolder == null || updateFolder.isEmpty()) {
                return "update";
            }
            
            return updateFolder;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read update folder from bukkit.yml, using default 'update': " + e.getMessage());
            return "update";
        }
    }
    
    /**
     * Set dry-run mode
     * @param dryRun true to enable dry-run mode
     */
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }
    
    /**
     * Check if dry-run mode is enabled
     * @return true if dry-run mode is enabled
     */
    public boolean isDryRun() {
        return dryRun;
    }
    
    /**
     * Get metrics as a map
     * @return Map with metrics counters
     */
    public Map<String, Long> getMetrics() {
        return Map.of(
            "checks_total", checksTotal.get(),
            "updates_found", updatesFound.get(),
            "downloads_success", downloadsSuccess.get(),
            "downloads_failed", downloadsFailed.get(),
            "errors", errors.get()
        );
    }
    
    /**
     * Get metrics in Prometheus format
     * @return Prometheus formatted metrics string
     */
    public String getMetricsPrometheus() {
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP hanafubuki_updater_checks_total Total number of version checks performed\n");
        sb.append("# TYPE hanafubuki_updater_checks_total counter\n");
        sb.append("hanafubuki_updater_checks_total ").append(checksTotal.get()).append("\n");
        sb.append("# HELP hanafubuki_updater_updates_found Total number of updates found\n");
        sb.append("# TYPE hanafubuki_updater_updates_found counter\n");
        sb.append("hanafubuki_updater_updates_found ").append(updatesFound.get()).append("\n");
        sb.append("# HELP hanafubuki_updater_downloads_success Total number of successful downloads\n");
        sb.append("# TYPE hanafubuki_updater_downloads_success counter\n");
        sb.append("hanafubuki_updater_downloads_success ").append(downloadsSuccess.get()).append("\n");
        sb.append("# HELP hanafubuki_updater_downloads_failed Total number of failed downloads\n");
        sb.append("# TYPE hanafubuki_updater_downloads_failed counter\n");
        sb.append("hanafubuki_updater_downloads_failed ").append(downloadsFailed.get()).append("\n");
        sb.append("# HELP hanafubuki_updater_errors Total number of errors\n");
        sb.append("# TYPE hanafubuki_updater_errors counter\n");
        sb.append("hanafubuki_updater_errors ").append(errors.get()).append("\n");
        return sb.toString();
    }
    
    /**
     * Get the number of configured plugins being tracked
     * @return Number of plugins in config
     */
    public int getPluginsTracked() {
        return configManager.getPlugins().size();
    }
    
    /**
     * Get the last check timestamp (placeholder - could be enhanced)
     * @return Current timestamp as last check time
     */
    public long getLastCheckTimestamp() {
        return System.currentTimeMillis();
    }
}