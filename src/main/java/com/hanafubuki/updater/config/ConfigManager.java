package com.hanafubuki.updater.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.hanafubuki.updater.PluginUpdater;

/**
 * Manages configuration for the Hanafubuki Plugin Updater
 */
public class ConfigManager {
    
    private Map<String, Object> config;
    private final JavaPlugin plugin;
    
    public ConfigManager() {
        this.config = new HashMap<>();
        this.plugin = PluginUpdater.getPlugin(PluginUpdater.class);
    }
    
    /**
     * Load configuration from the plugin's data folder
     * @param dataFolder The plugin's data folder
     * @throws IOException If there's an error reading the file
     */
    public void loadConfig(File dataFolder) throws IOException {
        // Create config file if it doesn't exist
        File configFile = new File(dataFolder, "config.yml");
        
        if (!configFile.exists()) {
            // Copy default config from resources
            plugin.saveResource("config.yml", false);
        }
        
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        config = yaml.getValues(true);
    }
    
    /**
     * Get global configuration settings
     * @return Global configuration map
     */
    public Map<String, Object> getGlobalConfig() {
        return (Map<String, Object>) config.get("global");
    }
    
    /**
     * Get update modes configuration
     * @return Modes configuration map
     */
    public Map<String, Object> getModesConfig() {
        return (Map<String, Object>) config.get("modes");
    }
    
    /**
     * Check if include-only mode is enabled
     * @return true if include-only mode is enabled, false otherwise
     */
    public boolean getIncludeOnlyMode() {
        Map<String, Object> modes = getModesConfig();
        return modes != null ? (Boolean) modes.getOrDefault("include_only", false) : false;
    }
    
    /**
     * Get the list of excluded plugins
     * @return List of excluded plugin names
     */
    public List<String> getExcludeList() {
        Map<String, Object> modes = getModesConfig();
        if (modes != null && modes.containsKey("exclude_list")) {
            return (List<String>) modes.get("exclude_list");
        }
        return List.of();
    }
    
    /**
     * Check if a plugin is in the excluded list
     * @param pluginName Name of the plugin to check
     * @return true if plugin is excluded, false otherwise
     */
    public boolean isPluginExcluded(String pluginName) {
        List<String> excludeList = getExcludeList();
        return excludeList.contains(pluginName);
    }
    
    /**
     * Check if a plugin is in the included list (when include_only mode is enabled)
     * @param pluginName Name of the plugin to check
     * @return true if plugin is included, false otherwise
     */
    public boolean isPluginIncluded(String pluginName) {
        // This would normally read from a configured "include_list" 
        // For now, we'll assume all plugins are included in include_only mode
        // This should be implemented based on your actual include list structure
        return true;
    }
    
    /**
     * Get the list of plugins to update
     * @return Map of plugin configurations
     */
    public Map<String, Object> getPlugins() {
        return (Map<String, Object>) config.get("plugins");
    }
    
    /**
     * Get details for a specific plugin
     * @param pluginName Name of the plugin
     * @return Plugin configuration map
     */
    public Map<String, Object> getPluginDetails(String pluginName) {
        Map<String, Object> plugins = getPlugins();
        if (plugins != null && plugins.containsKey(pluginName)) {
            return (Map<String, Object>) plugins.get(pluginName);
        }
        return new HashMap<>();
    }
    
    /**
     * Get Modrinth configuration
     * @return Modrinth configuration map
     */
    public Map<String, Object> getModrinthConfig() {
        return (Map<String, Object>) config.get("modrinth");
    }
    
    /**
     * Get GitHub configuration
     * @return GitHub configuration map
     */
    public Map<String, Object> getGitHubConfig() {
        return (Map<String, Object>) config.get("github");
    }
    
    /**
     * Get console output settings
     * @return Console configuration map
     */
    public Map<String, Object> getConsoleConfig() {
        return (Map<String, Object>) config.get("console");
    }
    
    /**
     * Get commit message template
     * @return Commit message template string
     */
    public String getCommitMessage() {
        return (String) config.getOrDefault("commit_message", "Plugin {plugin} updated, try restarting");
    }
}