package com.hanafubuki.updater.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.hanafubuki.updater.PluginUpdater;

/**
 * Manages configuration for the Hanafubuki Plugin Updater
 */
public class ConfigManager {
    
    private YamlConfiguration yaml;
    private final JavaPlugin plugin;
    
    public ConfigManager() {
        this.yaml = new YamlConfiguration();
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
        
        yaml = YamlConfiguration.loadConfiguration(configFile);
    }
    
    /**
     * Get global configuration settings
     * @return Global configuration map
     */
    public Map<String, Object> getGlobalConfig() {
        ConfigurationSection section = yaml.getConfigurationSection("global");
        return section != null ? section.getValues(false) : Map.of();
    }
    
    /**
     * Get update modes configuration
     * @return Modes configuration map
     */
    public Map<String, Object> getModesConfig() {
        ConfigurationSection section = yaml.getConfigurationSection("modes");
        return section != null ? section.getValues(false) : Map.of();
    }
    
    /**
     * Check if include-only mode is enabled
     * @return true if include-only mode is enabled, false otherwise
     */
    public boolean getIncludeOnlyMode() {
        return yaml.getBoolean("modes.include_only", false);
    }
    
    /**
     * Get the list of excluded plugins
     * @return List of excluded plugin names
     */
    public List<String> getExcludeList() {
        List<String> list = yaml.getStringList("modes.exclude_list");
        return list != null ? list : Collections.emptyList();
    }
    
    /**
     * Get the list of included plugins (for include-only mode)
     * @return List of included plugin names
     */
    public List<String> getIncludeList() {
        List<String> list = yaml.getStringList("modes.include_list");
        return list != null ? list : Collections.emptyList();
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
        List<String> includeList = getIncludeList();
        return includeList.contains(pluginName);
    }
    
    /**
     * Get the list of plugins to update
     * @return Map of plugin configurations
     */
    public Map<String, Object> getPlugins() {
        ConfigurationSection section = yaml.getConfigurationSection("plugins");
        if (section == null) {
            return Map.of();
        }
        Map<String, Object> result = new java.util.HashMap<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection pluginSection = section.getConfigurationSection(key);
            if (pluginSection != null) {
                result.put(key, pluginSection.getValues(false));
            }
        }
        return result;
    }
    
    /**
     * Get details for a specific plugin
     * @param pluginName Name of the plugin
     * @return Plugin configuration map
     */
    public Map<String, Object> getPluginDetails(String pluginName) {
        ConfigurationSection section = yaml.getConfigurationSection("plugins." + pluginName);
        return section != null ? section.getValues(false) : Map.of();
    }
    
    /**
     * Get Modrinth configuration
     * @return Modrinth configuration map
     */
    public Map<String, Object> getModrinthConfig() {
        ConfigurationSection section = yaml.getConfigurationSection("modrinth");
        return section != null ? section.getValues(false) : Map.of();
    }
    
    /**
     * Get GitHub configuration
     * @return GitHub configuration map
     */
    public Map<String, Object> getGitHubConfig() {
        ConfigurationSection section = yaml.getConfigurationSection("github");
        return section != null ? section.getValues(false) : Map.of();
    }
    
    /**
     * Get console output settings
     * @return Console configuration map
     */
    public Map<String, Object> getConsoleConfig() {
        ConfigurationSection section = yaml.getConfigurationSection("console");
        return section != null ? section.getValues(false) : Map.of();
    }
    
    /**
     * Get commit message template
     * @return Commit message template string
     */
    public String getCommitMessage() {
        return yaml.getString("commit_message", "Plugin {plugin} updated, try restarting");
    }
}