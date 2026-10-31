package com.hanafubuki.updater;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.ChatColor;

import com.hanafubuki.updater.config.ConfigManager;
import com.hanafubuki.updater.manager.RepositoryManager;
import com.hanafubuki.updater.util.VersionUtils;

/**
 * Main class for the Hanafubuki Plugin Updater
 * This tool updates Minecraft server plugins from Modrinth and GitHub sources
 */
public class PluginUpdater extends JavaPlugin {
    
    private ConfigManager configManager;
    private RepositoryManager repositoryManager;
    private ExecutorService executorService;
    
    @Override
    public void onEnable() {
        this.configManager = new ConfigManager();
        this.executorService = Executors.newFixedThreadPool(4);
        this.repositoryManager = new RepositoryManager(executorService);
        
        // Load configuration
        try {
            configManager.loadConfig(getDataFolder());
        } catch (IOException e) {
            getLogger().severe("Failed to load configuration: " + e.getMessage());
            e.printStackTrace();
            return;
        }
        
        getLogger().info("Hanafubuki Plugin Updater enabled!");
    }
    
    @Override
    public void onDisable() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        getLogger().info("Hanafubuki Plugin Updater disabled!");
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("updateplugins")) {
            if (!sender.hasPermission("hanafubuki.updater.update")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            
            // Handle subcommands
            if (args.length > 0 && args[0].equalsIgnoreCase("discover")) {
                handleDiscoverCommand(sender, args);
                return true;
            }
            
            // Run update process asynchronously
            CompletableFuture.runAsync(() -> {
                try {
                    performUpdates();
                } catch (Exception e) {
                    getLogger().severe("Error during plugin updates: " + e.getMessage());
                    e.printStackTrace();
                }
            });
            
            sender.sendMessage(ChatColor.GREEN + "Plugin update process started in background.");
            return true;
        }
        
        return false;
    }
    
    /**
     * Handle the /updateplugins discover command
     * Lists discovered plugins not in config and optionally adds them
     */
    private void handleDiscoverCommand(CommandSender sender, String[] args) {
        if (!configManager.getAutoDiscover()) {
            sender.sendMessage(ChatColor.RED + "Auto-discovery is disabled. Enable 'global.auto_discover' in config.yml to use this command.");
            return;
        }
        
        boolean shouldAdd = args.length > 1 && args[1].equalsIgnoreCase("add");
        
        CompletableFuture.runAsync(() -> {
            try {
                List<ConfigManager.DiscoveredPlugin> discovered = configManager.discoverUnknownPlugins();
                
                if (discovered.isEmpty()) {
                    sender.sendMessage(ChatColor.GREEN + "No unknown plugins found - all installed plugins are already configured!");
                    return;
                }
                
                if (!shouldAdd) {
                    sender.sendMessage(ChatColor.GOLD + "=== Discovered " + discovered.size() + " unknown plugin(s) ===");
                    for (ConfigManager.DiscoveredPlugin dp : discovered) {
                        sender.sendMessage(ChatColor.YELLOW + "  - " + dp.name() + " v" + dp.version() + " (source: " + dp.source() + ")");
                    }
                    sender.sendMessage(ChatColor.AQUA + "Use /updateplugins discover add to add all to config.yml");
                    return;
                }
                
                // Add all discovered plugins to config
                int added = 0;
                for (ConfigManager.DiscoveredPlugin dp : discovered) {
                    String projectId = null;
                    String repo = null;
                    
                    // For GitHub plugins, we need the repo - can't auto-detect reliably
                    // For Modrinth, we'll rely on hash-based lookup (project_id optional)
                    if ("github".equals(dp.source())) {
                        // Try to get repo from plugin.yml
                        repo = extractGitHubRepo(dp.jarFile());
                    }
                    
                    if (configManager.addDiscoveredPlugin(dp.name(), dp.version(), dp.source(), projectId, repo)) {
                        added++;
                        sender.sendMessage(ChatColor.GREEN + "Added: " + dp.name() + " v" + dp.version() + " (" + dp.source() + ")");
                    } else {
                        sender.sendMessage(ChatColor.RED + "Failed to add: " + dp.name());
                    }
                }
                
                sender.sendMessage(ChatColor.GOLD + "Added " + added + " of " + discovered.size() + " plugins to config.yml");
                sender.sendMessage(ChatColor.YELLOW + "Note: GitHub plugins need 'repo' (owner/repo) configured manually if not auto-detected.");
                
            } catch (Exception e) {
                getLogger().severe("Error during plugin discovery: " + e.getMessage());
                e.printStackTrace();
                sender.sendMessage(ChatColor.RED + "Error during discovery: " + e.getMessage());
            }
        });
    }
    
    /**
     * Try to extract GitHub repo from plugin.yml
     */
    private String extractGitHubRepo(File jarFile) {
        if (jarFile == null || !jarFile.exists()) {
            return null;
        }
        
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
            var entry = jar.getEntry("plugin.yml");
            if (entry != null) {
                try (var is = jar.getInputStream(entry);
                     var reader = new java.io.InputStreamReader(is)) {
                    var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
                    
                    // Check for website URL that might be GitHub
                    String website = yaml.getString("website");
                    if (website != null && website.contains("github.com")) {
                        // Extract owner/repo from github.com URL
                        // e.g., https://github.com/Owner/Repo -> Owner/Repo
                        try {
                            java.net.URL url = new java.net.URL(website);
                            String path = url.getPath();
                            if (path.startsWith("/")) path = path.substring(1);
                            String[] parts = path.split("/");
                            if (parts.length >= 2) {
                                return parts[0] + "/" + parts[1];
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
    
    /**
     * Performs the actual plugin update process
     */
    private void performUpdates() {
        try {
            // Check if we're in include-only mode
            boolean includeOnly = configManager.getIncludeOnlyMode();
            
            // Get plugin list
            Map<String, Object> plugins = configManager.getPlugins();
            
            // Collect all update tasks
            List<CompletableFuture<?>> updateFutures = new java.util.ArrayList<>();
            
            // Process plugins based on mode
            if (includeOnly) {
                updateIncludedPlugins(plugins, updateFutures);
            } else {
                updateAllExceptExcluded(plugins, updateFutures);
            }
            
            // Wait for all updates to complete
            if (!updateFutures.isEmpty()) {
                CompletableFuture.allOf(updateFutures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> getLogger().info("All plugin updates completed"))
                    .exceptionally(ex -> {
                        getLogger().severe("Error during plugin updates: " + ex.getMessage());
                        return null;
                    })
                    .join(); // Wait for completion
            }
            
        } catch (Exception e) {
            getLogger().severe("Error during plugin updates: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Updates only plugins that are explicitly included in the configuration
     * @param plugins List of plugins to process
     * @param futures List to collect update futures
     */
    private void updateIncludedPlugins(Map<String, Object> plugins, List<CompletableFuture<?>> futures) {
        getLogger().info("Starting update process - Include-only mode");
        
        for (Map.Entry<String, Object> entry : plugins.entrySet()) {
            String pluginName = entry.getKey();
            
            // Check if plugin is in the included list
            if (configManager.isPluginIncluded(pluginName)) {
                futures.add(updateSinglePlugin(pluginName));
            } else {
                getLogger().info("Skipping " + pluginName + " - not in include list");
            }
        }
    }
    
    /**
     * Updates all plugins except those explicitly excluded
     * @param plugins List of plugins to process
     * @param futures List to collect update futures
     */
    private void updateAllExceptExcluded(Map<String, Object> plugins, List<CompletableFuture<?>> futures) {
        getLogger().info("Starting update process - Update all except excluded");
        
        for (Map.Entry<String, Object> entry : plugins.entrySet()) {
            String pluginName = entry.getKey();
            
            // Check if plugin is in the excluded list
            if (!configManager.isPluginExcluded(pluginName)) {
                futures.add(updateSinglePlugin(pluginName));
            } else {
                getLogger().info("Skipping " + pluginName + " - excluded");
            }
        }
    }
    
    /**
     * Updates a single plugin
     * @param pluginName Name of the plugin to update
     * @return CompletableFuture that completes when the update is done
     */
    private CompletableFuture<?> updateSinglePlugin(String pluginName) {
        return CompletableFuture.runAsync(() -> {
            getLogger().info("Checking updates for " + pluginName);
            
            try {
                // Get plugin details from configuration
                Map<String, Object> pluginDetails = configManager.getPluginDetails(pluginName);
                
                // Validate plugin configuration
                if (!configManager.validatePluginConfig(pluginName, pluginDetails)) {
                    getLogger().severe("Skipping " + pluginName + " due to invalid configuration");
                    return;
                }
                
                // Determine source (Modrinth or GitHub)
                String source = (String) pluginDetails.get("source");
                
                // Update based on source
                if ("modrinth".equalsIgnoreCase(source)) {
                    repositoryManager.updateFromModrinth(pluginName, pluginDetails);
                } else if ("github".equalsIgnoreCase(source)) {
                    repositoryManager.updateFromGitHub(pluginName, pluginDetails);
                } else {
                    getLogger().warning("Unknown source for " + pluginName + ": " + source);
                }
                
            } catch (Exception e) {
                getLogger().severe("Failed to update " + pluginName + ": " + e.getMessage());
                e.printStackTrace();
            }
        }, executorService);
    }
}