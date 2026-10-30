package com.hanafubuki.updater;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
    
    @Override
    public void onEnable() {
        this.configManager = new ConfigManager();
        this.repositoryManager = new RepositoryManager();
        
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
        getLogger().info("Hanafubuki Plugin Updater disabled!");
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("updateplugins")) {
            if (!sender.hasPermission("hanafubuki.updater.update")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
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
     * Performs the actual plugin update process
     */
    private void performUpdates() {
        try {
            // Check if we're in include-only mode
            boolean includeOnly = configManager.getIncludeOnlyMode();
            
            // Get plugin list
            Map<String, Object> plugins = configManager.getPlugins();
            
            // Process plugins based on mode
            if (includeOnly) {
                updateIncludedPlugins(plugins);
            } else {
                updateAllExceptExcluded(plugins);
            }
        } catch (Exception e) {
            getLogger().severe("Error during plugin updates: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Updates only plugins that are explicitly included in the configuration
     * @param plugins List of plugins to process
     */
    private void updateIncludedPlugins(Map<String, Object> plugins) {
        getLogger().info("Starting update process - Include-only mode");
        
        for (Map.Entry<String, Object> entry : plugins.entrySet()) {
            String pluginName = entry.getKey();
            
            // Check if plugin is in the included list
            if (configManager.isPluginIncluded(pluginName)) {
                updateSinglePlugin(pluginName);
            } else {
                getLogger().info("Skipping " + pluginName + " - not in include list");
            }
        }
    }
    
    /**
     * Updates all plugins except those explicitly excluded
     * @param plugins List of plugins to process
     */
    private void updateAllExceptExcluded(Map<String, Object> plugins) {
        getLogger().info("Starting update process - Update all except excluded");
        
        for (Map.Entry<String, Object> entry : plugins.entrySet()) {
            String pluginName = entry.getKey();
            
            // Check if plugin is in the excluded list
            if (!configManager.isPluginExcluded(pluginName)) {
                updateSinglePlugin(pluginName);
            } else {
                getLogger().info("Skipping " + pluginName + " - excluded");
            }
        }
    }
    
    /**
     * Updates a single plugin
     * @param pluginName Name of the plugin to update
     */
    private void updateSinglePlugin(String pluginName) {
        getLogger().info("Checking updates for " + pluginName);
        
        try {
            // Get plugin details from configuration
            Map<String, Object> pluginDetails = configManager.getPluginDetails(pluginName);
            
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
    }
}