package com.hanafubuki.updater.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

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
    
    // Package-private constructor for testing
    ConfigManager(JavaPlugin plugin) {
        this.yaml = new YamlConfiguration();
        this.plugin = plugin;
    }
    
    /**
     * Load configuration from the plugin's data folder
     * @param dataFolder The plugin's data folder
     * @throws IOException If there's an error reading the file
     */
    public void loadConfig(File dataFolder) throws IOException {
        // Create config file if it doesn't exist
        File configFile = new File(dataFolder, "config.yml");
        
        if (!configFile.exists() && plugin != null) {
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
     * Get Modrinth API timeout in seconds
     * @return API timeout (default: 10)
     */
    public int getModrinthApiTimeout() {
        return yaml.getInt("modrinth.timeout", 10);
    }
    
    /**
     * Get Modrinth download timeout in seconds
     * @return Download timeout (default: 60)
     */
    public int getModrinthDownloadTimeout() {
        return yaml.getInt("modrinth.download_timeout", 60);
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
     * Get GitHub API timeout in seconds
     * @return API timeout (default: 10)
     */
    public int getGitHubApiTimeout() {
        return yaml.getInt("github.timeout", 10);
    }
    
    /**
     * Get GitHub download timeout in seconds
     * @return Download timeout (default: 60)
     */
    public int getGitHubDownloadTimeout() {
        return yaml.getInt("github.download_timeout", 60);
    }
    
    /**
     * Get Hangar configuration
     * @return Hangar configuration map
     */
    public Map<String, Object> getHangarConfig() {
        ConfigurationSection section = yaml.getConfigurationSection("hangar");
        return section != null ? section.getValues(false) : Map.of();
    }

    /**
     * Get Hangar API timeout in seconds
     * @return API timeout (default: 10)
     */
    public int getHangarApiTimeout() {
        return yaml.getInt("hangar.timeout", 10);
    }

    /**
     * Get Hangar download timeout in seconds
     * @return Download timeout (default: 60)
     */
    public int getHangarDownloadTimeout() {
        return yaml.getInt("hangar.download_timeout", 60);
    }

    /**
     * Get SpigotMC configuration
     * @return SpigotMC configuration map
     */
    public Map<String, Object> getSpigotMCConfig() {
        ConfigurationSection section = yaml.getConfigurationSection("spigotmc");
        return section != null ? section.getValues(false) : Map.of();
    }

    /**
     * Get SpigotMC API timeout in seconds
     * @return API timeout (default: 10)
     */
    public int getSpigotMCApiTimeout() {
        return yaml.getInt("spigotmc.timeout", 10);
    }

    /**
     * Get SpigotMC download timeout in seconds
     * @return Download timeout (default: 60)
     */
    public int getSpigotMCDownloadTimeout() {
        return yaml.getInt("spigotmc.download_timeout", 60);
    }

    /**
     * Get Local file source configuration
     * @return Local configuration map
     */
    public Map<String, Object> getLocalConfig() {
        ConfigurationSection section = yaml.getConfigurationSection("local");
        return section != null ? section.getValues(false) : Map.of();
    }

    /**
     * Get Local file source timeout in seconds
     * @return Timeout (default: 5)
     */
    public int getLocalTimeout() {
        return yaml.getInt("local.timeout", 5);
    }

    /**
     * Get Custom HTTP source configuration
     * @return Custom HTTP configuration map
     */
    public Map<String, Object> getCustomHttpConfig() {
        ConfigurationSection section = yaml.getConfigurationSection("custom");
        return section != null ? section.getValues(false) : Map.of();
    }

    /**
     * Get Custom HTTP API timeout in seconds
     * @return API timeout (default: 10)
     */
    public int getCustomHttpApiTimeout() {
        return yaml.getInt("custom.timeout", 10);
    }

    /**
     * Get Custom HTTP download timeout in seconds
     * @return Download timeout (default: 60)
     */
    public int getCustomHttpDownloadTimeout() {
        return yaml.getInt("custom.download_timeout", 60);
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
     * Check if JSON format logging is enabled
     * @return true if JSON format is enabled, false otherwise
     */
    public boolean getConsoleJsonFormat() {
        return yaml.getBoolean("console.json_format", false);
    }

    /**
     * Get Modrinth retry configuration
     * @return Map with max_retries, base_delay_ms, max_delay_ms
     */
    public Map<String, Object> getModrinthRetryConfig() {
        ConfigurationSection section = yaml.getConfigurationSection("modrinth.retry");
        return section != null ? section.getValues(false) : Map.of();
    }

    /**
     * Get Modrinth max retries
     * @return Max retries (default: 3)
     */
    public int getModrinthMaxRetries() {
        return yaml.getInt("modrinth.retry.max_retries", 3);
    }

    /**
     * Get Modrinth base delay in milliseconds
     * @return Base delay in ms (default: 1000)
     */
    public int getModrinthBaseDelayMs() {
        return yaml.getInt("modrinth.retry.base_delay_ms", 1000);
    }

    /**
     * Get Modrinth max delay in milliseconds
     * @return Max delay in ms (default: 10000)
     */
    public int getModrinthMaxDelayMs() {
        return yaml.getInt("modrinth.retry.max_delay_ms", 10000);
    }

    /**
     * Get GitHub retry configuration
     * @return Map with max_retries, base_delay_ms, max_delay_ms
     */
    public Map<String, Object> getGitHubRetryConfig() {
        ConfigurationSection section = yaml.getConfigurationSection("github.retry");
        return section != null ? section.getValues(false) : Map.of();
    }

    /**
     * Get GitHub max retries
     * @return Max retries (default: 3)
     */
    public int getGitHubMaxRetries() {
        return yaml.getInt("github.retry.max_retries", 3);
    }

    /**
     * Get GitHub base delay in milliseconds
     * @return Base delay in ms (default: 1000)
     */
    public int getGitHubBaseDelayMs() {
        return yaml.getInt("github.retry.base_delay_ms", 1000);
    }

    /**
     * Get GitHub max delay in milliseconds
     * @return Max delay in ms (default: 10000)
     */
    public int getGitHubMaxDelayMs() {
        return yaml.getInt("github.retry.max_delay_ms", 10000);
    }

    /**
     * Get health check configuration
     * @return Health check configuration map
     */
    public Map<String, Object> getHealthCheckConfig() {
        ConfigurationSection section = yaml.getConfigurationSection("health_check");
        return section != null ? section.getValues(false) : Map.of();
    }

    /**
     * Check if health check endpoint is enabled
     * @return true if enabled, false otherwise
     */
    public boolean isHealthCheckEnabled() {
        return yaml.getBoolean("health_check.enabled", false);
    }

    /**
     * Get health check port
     * @return Port number (default: 8080)
     */
    public int getHealthCheckPort() {
        return yaml.getInt("health_check.port", 8080);
    }
    
    /**
     * Get commit message template
     * @return Commit message template string
     */
    public String getCommitMessage() {
        return yaml.getString("commit_message", "Plugin {plugin} updated, try restarting");
    }

    /**
     * Check if auto-update of config.yml is enabled
     * @return true if config should be updated after successful plugin update
     */
    public boolean getAutoUpdateConfig() {
        return yaml.getBoolean("global.auto_update_config", true);
    }

    /**
     * Check if auto-discovery of plugins is enabled
     * @return true if auto-discovery is enabled, false otherwise
     */
    public boolean getAutoDiscover() {
        return yaml.getBoolean("global.auto_discover", false);
    }

    /**
     * Check if auto-discovery on startup is enabled
     * @return true if auto-discovery on startup is enabled, false otherwise
     */
    public boolean getAutoDiscoverOnStartup() {
        return yaml.getBoolean("global.auto_discover_on_startup", false);
    }

    /**
     * Update the version of a plugin in config.yml
     * @param pluginName Name of the plugin
     * @param newVersion New version string
     * @return true if updated successfully
     */
    public boolean updatePluginVersion(String pluginName, String newVersion) {
        try {
            ConfigurationSection pluginsSection = yaml.getConfigurationSection("plugins");
            if (pluginsSection == null) {
                return false;
            }
            
            ConfigurationSection pluginSection = pluginsSection.getConfigurationSection(pluginName);
            if (pluginSection == null) {
                return false;
            }
            
            pluginSection.set("version", newVersion);
            
            // Save to file
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            yaml.save(configFile);
            
            if (plugin != null) {
                plugin.getLogger().info("Updated config.yml: " + pluginName + " version -> " + newVersion);
            }
            return true;
        } catch (IOException e) {
            if (plugin != null) {
                plugin.getLogger().severe("Failed to update config.yml for " + pluginName + ": " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Validate plugin configuration
     * @param pluginName Name of the plugin
     * @param pluginDetails Plugin configuration map
     * @return true if valid, false if invalid
     */
    public boolean validatePluginConfig(String pluginName, Map<String, Object> pluginDetails) {
        Logger logger = plugin != null ? plugin.getLogger() : Logger.getLogger("ConfigManagerTest");
        
        // Check source field
        Object sourceObj = pluginDetails.get("source");
        if (sourceObj == null) {
            logger.severe("Plugin '" + pluginName + "' is missing required field 'source' (must be 'modrinth', 'github', 'hangar', 'spigotmc', 'local', or 'custom')");
            return false;
        }
        String source = sourceObj.toString().toLowerCase();
        
        if (!"modrinth".equals(source) && !"github".equals(source) && !"hangar".equals(source) && 
            !"spigotmc".equals(source) && !"local".equals(source) && !"custom".equals(source)) {
            logger.severe("Plugin '" + pluginName + "' has invalid source: '" + source + "' (must be 'modrinth', 'github', 'hangar', 'spigotmc', 'local', or 'custom')");
            return false;
        }
        
        // Check version field (required for all sources)
        Object versionObj = pluginDetails.get("version");
        if (versionObj == null || versionObj.toString().trim().isEmpty()) {
            logger.severe("Plugin '" + pluginName + "' is missing required field 'version' (non-empty string)");
            return false;
        }
        
        // Source-specific validation
        if ("github".equals(source)) {
            Object repoObj = pluginDetails.get("repo");
            if (repoObj == null || repoObj.toString().trim().isEmpty()) {
                logger.severe("Plugin '" + pluginName + "' (github source) is missing required field 'repo' (format: owner/repo)");
                return false;
            }
            String repo = repoObj.toString().trim();
            if (!repo.contains("/")) {
                logger.severe("Plugin '" + pluginName + "' (github source) has invalid repo format: '" + repo + "' (must be owner/repo)");
                return false;
            }
        } else if ("hangar".equals(source)) {
            Object projectSlugObj = pluginDetails.get("project_slug");
            if (projectSlugObj == null || projectSlugObj.toString().trim().isEmpty()) {
                logger.severe("Plugin '" + pluginName + "' (hangar source) is missing required field 'project_slug' (format: owner/slug)");
                return false;
            }
            String projectSlug = projectSlugObj.toString().trim();
            if (!projectSlug.contains("/")) {
                logger.severe("Plugin '" + pluginName + "' (hangar source) has invalid project_slug format: '" + projectSlug + "' (must be owner/slug)");
                return false;
            }
        } else if ("spigotmc".equals(source)) {
            Object resourceIdObj = pluginDetails.get("resource_id");
            Object resourceNameObj = pluginDetails.get("resource_name");
            if ((resourceIdObj == null || resourceIdObj.toString().trim().isEmpty()) &&
                (resourceNameObj == null || resourceNameObj.toString().trim().isEmpty())) {
                logger.severe("Plugin '" + pluginName + "' (spigotmc source) requires either 'resource_id' (numeric) or 'resource_name'");
                return false;
            }
            if (resourceIdObj != null) {
                try {
                    Integer.parseInt(resourceIdObj.toString().trim());
                } catch (NumberFormatException e) {
                    logger.severe("Plugin '" + pluginName + "' (spigotmc source) has invalid 'resource_id': must be numeric");
                    return false;
                }
            }
        } else if ("local".equals(source)) {
            Object pathObj = pluginDetails.get("path");
            if (pathObj == null || pathObj.toString().trim().isEmpty()) {
                logger.severe("Plugin '" + pluginName + "' (local source) is missing required field 'path' (directory or file path)");
                return false;
            }
        } else if ("custom".equals(source)) {
            Object urlObj = pluginDetails.get("url");
            if (urlObj == null || urlObj.toString().trim().isEmpty()) {
                logger.severe("Plugin '" + pluginName + "' (custom source) is missing required field 'url' (REST endpoint)");
                return false;
            }
        }
        
        return true;
    }

    /**
     * Get all configured plugin names
     * @return Set of configured plugin names
     */
    public Set<String> getConfiguredPluginNames() {
        ConfigurationSection section = yaml.getConfigurationSection("plugins");
        return section != null ? section.getKeys(false) : Collections.emptySet();
    }

    /**
     * Discover installed plugins that are not in config
     * @return List of DiscoveredPlugin objects for plugins not in config
     */
    public List<DiscoveredPlugin> discoverUnknownPlugins() {
        List<DiscoveredPlugin> discovered = new ArrayList<>();
        
        if (plugin == null) {
            return discovered;
        }
        
        Set<String> configuredPlugins = getConfiguredPluginNames();
        Logger logger = plugin.getLogger();
        
        for (org.bukkit.plugin.Plugin loadedPlugin : org.bukkit.Bukkit.getPluginManager().getPlugins()) {
            String pluginName = loadedPlugin.getName();
            
            // Skip if already configured
            if (configuredPlugins.contains(pluginName)) {
                continue;
            }
            
            // Skip self
            if (pluginName.equals(plugin.getName())) {
                continue;
            }
            
            try {
                String version = loadedPlugin.getPluginMeta().getVersion();
                File jarFile = getPluginJarFile(loadedPlugin);
                DiscoveredPlugin dp = discoverPluginDetails(loadedPlugin, pluginName, version, jarFile);
                
                discovered.add(dp);
                logger.info("Auto-discovered and added to config: " + pluginName + " v" + version + " (" + dp.source() + ")");
            } catch (Exception e) {
                logger.warning("Failed to discover plugin " + pluginName + ": " + e.getMessage());
            }
        }
        
        return discovered;
    }

    /**
     * Discover detailed information about a single plugin
     * @param loadedPlugin The loaded Bukkit plugin
     * @param pluginName Name of the plugin
     * @param version Current version
     * @param jarFile JAR file of the plugin
     * @return DiscoveredPlugin with all extracted metadata
     */
    private DiscoveredPlugin discoverPluginDetails(org.bukkit.plugin.Plugin loadedPlugin, String pluginName, String version, File jarFile) {
        String source = detectPluginSource(jarFile, pluginName);
        String projectId = null;
        String repo = null;
        String hangarSlug = null;
        String spigotmcResourceId = null;
        String spigotmcResourceName = null;
        String localPath = null;
        String customUrl = null;
        
        if (jarFile != null && jarFile.exists()) {
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
                var entry = jar.getEntry("plugin.yml");
                if (entry != null) {
                    try (var is = jar.getInputStream(entry);
                         var reader = new java.io.InputStreamReader(is)) {
                        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
                        
                        // Extract source-specific metadata
                        projectId = yaml.getString("modrinth.project_id");
                        hangarSlug = yaml.getString("hangar.slug");
                        spigotmcResourceId = yaml.getString("spigotmc.resource_id");
                        spigotmcResourceName = yaml.getString("spigotmc.resource_name");
                        localPath = yaml.getString("local.path");
                        customUrl = yaml.getString("custom.url");
                        
                        // Try to extract GitHub repo from website
                        if ("github".equals(source)) {
                            repo = extractGitHubRepoFromWebsite(yaml);
                        }
                        
                        // Try to extract Hangar slug from website
                        if ("hangar".equals(source) && hangarSlug == null) {
                            hangarSlug = extractHangarSlugFromWebsite(yaml);
                        }
                        
                        // Try to extract SpigotMC resource from website
                        if ("spigotmc".equals(source) && spigotmcResourceId == null && spigotmcResourceName == null) {
                            extractSpigotMCFromWebsite(yaml);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        
        return new DiscoveredPlugin(pluginName, version, source, jarFile, 
            projectId, repo, hangarSlug, spigotmcResourceId, spigotmcResourceName, localPath, customUrl);
    }

    /**
     * Extract GitHub repo from website URL in plugin.yml
     */
    private String extractGitHubRepoFromWebsite(org.bukkit.configuration.file.YamlConfiguration yaml) {
        String website = yaml.getString("website");
        if (website != null && website.contains("github.com")) {
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
        return null;
    }

    /**
     * Extract Hangar slug from website URL in plugin.yml
     */
    private String extractHangarSlugFromWebsite(org.bukkit.configuration.file.YamlConfiguration yaml) {
        String website = yaml.getString("website");
        if (website != null && website.contains("hangar.papermc.io")) {
            try {
                java.net.URL url = new java.net.URL(website);
                String path = url.getPath();
                if (path.startsWith("/")) path = path.substring(1);
                // hangar.papermc.io/plugins/owner/slug
                String[] parts = path.split("/");
                if (parts.length >= 3 && "plugins".equals(parts[0])) {
                    return parts[1] + "/" + parts[2];
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Extract SpigotMC resource ID/name from website URL in plugin.yml
     */
    private void extractSpigotMCFromWebsite(org.bukkit.configuration.file.YamlConfiguration yaml) {
        String website = yaml.getString("website");
        if (website != null && website.contains("spigotmc.org")) {
            // SpigotMC URLs are typically like: https://www.spigotmc.org/resources/name.12345/
            // We can't reliably extract resource_id from URL alone without API call
            // This is a placeholder for future enhancement
        }
    }

    /**
     * Get the JAR file for a plugin using reflection
     */
    private File getPluginJarFile(org.bukkit.plugin.Plugin loadedPlugin) {
        try {
            java.net.URL location = loadedPlugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                return new File(location.toURI());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Detect plugin source from plugin.yml in JAR
     * Checks for modrinth/github/hangar/spigotmc metadata or defaults to modrinth
     */
    private String detectPluginSource(File jarFile, String pluginName) {
        if (jarFile == null || !jarFile.exists()) {
            return "modrinth"; // Default
        }
        
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
            var entry = jar.getEntry("plugin.yml");
            if (entry != null) {
                try (var is = jar.getInputStream(entry);
                     var reader = new java.io.InputStreamReader(is)) {
                    var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
                    
                    // Check for explicit source field
                    String source = yaml.getString("source");
                    if (source != null) {
                        String lower = source.toLowerCase();
                        if ("modrinth".equals(lower) || "github".equals(lower) || "hangar".equals(lower) || 
                            "spigotmc".equals(lower) || "local".equals(lower) || "custom".equals(lower)) {
                            return lower;
                        }
                    }
                    
                    // Check for custom fields indicating source
                    String modrinthProjectId = yaml.getString("modrinth.project_id");
                    if (modrinthProjectId != null && !modrinthProjectId.isEmpty()) {
                        return "modrinth";
                    }
                    
                    String hangarSlug = yaml.getString("hangar.slug");
                    if (hangarSlug != null && !hangarSlug.isEmpty()) {
                        return "hangar";
                    }
                    
                    String spigotmcResourceId = yaml.getString("spigotmc.resource_id");
                    if (spigotmcResourceId != null && !spigotmcResourceId.isEmpty()) {
                        return "spigotmc";
                    }
                    
                    String localPath = yaml.getString("local.path");
                    if (localPath != null && !localPath.isEmpty()) {
                        return "local";
                    }
                    
                    String customUrl = yaml.getString("custom.url");
                    if (customUrl != null && !customUrl.isEmpty()) {
                        return "custom";
                    }
                    
                    // Check for website/URL that might indicate source
                    String website = yaml.getString("website");
                    if (website != null) {
                        String lowerWebsite = website.toLowerCase();
                        if (lowerWebsite.contains("modrinth.com")) {
                            return "modrinth";
                        }
                        if (lowerWebsite.contains("github.com")) {
                            return "github";
                        }
                        if (lowerWebsite.contains("hangar.papermc.io")) {
                            return "hangar";
                        }
                        if (lowerWebsite.contains("spigotmc.org")) {
                            return "spigotmc";
                        }
                    }
                    
                    // Check authors/description for hints
                    List<String> authors = yaml.getStringList("authors");
                    String description = yaml.getString("description", "");
                    String combined = String.join(" ", authors) + " " + description;
                    String lowerCombined = combined.toLowerCase();
                    if (lowerCombined.contains("modrinth")) {
                        return "modrinth";
                    }
                    if (lowerCombined.contains("hangar")) {
                        return "hangar";
                    }
                    if (lowerCombined.contains("spigotmc")) {
                        return "spigotmc";
                    }
                    if (lowerCombined.contains("github")) {
                        return "github";
                    }
                }
            }
        } catch (Exception ignored) {
        }
        
        return "modrinth"; // Default to modrinth
    }

    /**
     * Add a discovered plugin to config.yml
     * @param pluginName Name of the plugin
     * @param version Current version
     * @param source Source (modrinth, github, hangar, spigotmc, local, custom)
     * @param projectId Optional Modrinth project ID
     * @param repo Optional GitHub repo (owner/repo)
     * @param hangarSlug Optional Hangar project slug (owner/slug)
     * @param spigotmcResourceId Optional SpigotMC resource ID
     * @param spigotmcResourceName Optional SpigotMC resource name
     * @param localPath Optional local file path
     * @param customUrl Optional custom HTTP URL
     * @return true if added successfully
     */
    public boolean addDiscoveredPlugin(String pluginName, String version, String source, 
                                        String projectId, String repo, String hangarSlug,
                                        String spigotmcResourceId, String spigotmcResourceName,
                                        String localPath, String customUrl) {
        try {
            ConfigurationSection pluginsSection = yaml.getConfigurationSection("plugins");
            if (pluginsSection == null) {
                pluginsSection = yaml.createSection("plugins");
            }
            
            ConfigurationSection pluginSection = pluginsSection.createSection(pluginName);
            pluginSection.set("source", source);
            pluginSection.set("version", version);
            
            if ("modrinth".equals(source) && projectId != null && !projectId.isEmpty()) {
                pluginSection.set("project_id", projectId);
            }
            
            if ("github".equals(source) && repo != null && !repo.isEmpty()) {
                pluginSection.set("repo", repo);
            }
            
            if ("hangar".equals(source) && hangarSlug != null && !hangarSlug.isEmpty()) {
                pluginSection.set("project_slug", hangarSlug);
            }
            
            if ("spigotmc".equals(source)) {
                if (spigotmcResourceId != null && !spigotmcResourceId.isEmpty()) {
                    try {
                        pluginSection.set("resource_id", Integer.parseInt(spigotmcResourceId));
                    } catch (NumberFormatException e) {
                        // Fall back to resource_name
                        if (spigotmcResourceName != null && !spigotmcResourceName.isEmpty()) {
                            pluginSection.set("resource_name", spigotmcResourceName);
                        }
                    }
                } else if (spigotmcResourceName != null && !spigotmcResourceName.isEmpty()) {
                    pluginSection.set("resource_name", spigotmcResourceName);
                }
            }
            
            if ("local".equals(source) && localPath != null && !localPath.isEmpty()) {
                pluginSection.set("path", localPath);
            }
            
            if ("custom".equals(source) && customUrl != null && !customUrl.isEmpty()) {
                pluginSection.set("url", customUrl);
            }
            
            // Save to file
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            yaml.save(configFile);
            
            if (plugin != null) {
                plugin.getLogger().info("Added discovered plugin to config.yml: " + pluginName);
            }
            return true;
        } catch (IOException e) {
            if (plugin != null) {
                plugin.getLogger().severe("Failed to add discovered plugin " + pluginName + ": " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Record for a discovered plugin
     */
    public record DiscoveredPlugin(
        String name,
        String version,
        String source,
        File jarFile,
        String projectId,
        String repo,
        String hangarSlug,
        String spigotmcResourceId,
        String spigotmcResourceName,
        String localPath,
        String customUrl
    ) {
        // Backward compatibility constructor
        public DiscoveredPlugin(String name, String version, String source, File jarFile) {
            this(name, version, source, jarFile, null, null, null, null, null, null, null);
        }
    }
}