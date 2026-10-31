package com.hanafubuki.updater;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

import com.hanafubuki.updater.config.ConfigManager;
import com.hanafubuki.updater.manager.RepositoryManager;
import com.hanafubuki.updater.util.VersionUtils;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

/**
 * Main class for the Hanafubuki Plugin Updater
 * This tool updates Minecraft server plugins from Modrinth and GitHub sources
 */
public class PluginUpdater extends JavaPlugin {
    
    private ConfigManager configManager;
    private RepositoryManager repositoryManager;
    private ExecutorService executorService;
    private HttpServer healthCheckServer;
    
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
        
        // Start health check endpoint if enabled
        startHealthCheckEndpoint();
        
        // Run auto-discovery on startup if enabled
        if (configManager.getAutoDiscoverOnStartup()) {
            runAutoDiscoveryOnStartup();
        }
        
        getLogger().info("Hanafubuki Plugin Updater enabled!");
    }
    
    @Override
    public void onDisable() {
        // Stop health check server
        if (healthCheckServer != null) {
            healthCheckServer.stop(0);
        }
        
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
            if (args.length > 0) {
                String subCommand = args[0].toLowerCase();
                
                if (subCommand.equals("discover")) {
                    handleDiscoverCommand(sender, args);
                    return true;
                } else if (subCommand.equals("reload")) {
                    handleReloadCommand(sender);
                    return true;
                } else if (subCommand.equals("metrics")) {
                    handleMetricsCommand(sender, args);
                    return true;
                } else if (subCommand.equals("--dry-run") || subCommand.equals("dry-run")) {
                    // Run update process in dry-run mode
                    handleDryRunCommand(sender);
                    return true;
                }
            }
            
            // Check for --dry-run flag anywhere in args
            boolean dryRunFlag = false;
            for (String arg : args) {
                if (arg.equalsIgnoreCase("--dry-run") || arg.equalsIgnoreCase("-d")) {
                    dryRunFlag = true;
                    break;
                }
            }
            
            final boolean dryRun = dryRunFlag;
            
            // Run update process asynchronously
            CompletableFuture.runAsync(() -> {
                try {
                    if (dryRun) {
                        repositoryManager.setDryRun(true);
                        getLogger().info("Starting plugin update process in DRY-RUN mode");
                    }
                    performUpdates();
                } catch (Exception e) {
                    getLogger().severe("Error during plugin updates: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    repositoryManager.setDryRun(false);
                }
            });
            
            String message = dryRun 
                ? ChatColor.YELLOW + "Plugin update process started in DRY-RUN mode (no changes will be made)."
                : ChatColor.GREEN + "Plugin update process started in background.";
            sender.sendMessage(message);
            return true;
        }
        
        return false;
    }
    
    /**
     * Handle the /updateplugins --dry-run command
     */
    private void handleDryRunCommand(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Starting plugin update check in DRY-RUN mode...");
        
        CompletableFuture.runAsync(() -> {
            try {
                repositoryManager.setDryRun(true);
                getLogger().info("Starting plugin update process in DRY-RUN mode");
                performUpdates();
            } catch (Exception e) {
                getLogger().severe("Error during plugin updates: " + e.getMessage());
                e.printStackTrace();
            } finally {
                repositoryManager.setDryRun(false);
            }
        });
        
        sender.sendMessage(ChatColor.YELLOW + "DRY-RUN update check started. Check console for what would be updated.");
    }
    
    /**
     * Handle the /updateplugins reload command
     */
    private void handleReloadCommand(CommandSender sender) {
        try {
            // Reload config
            configManager.loadConfig(getDataFolder());
            
            // Recreate RepositoryManager with new settings
            // Shutdown old executor and create new one
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            this.executorService = Executors.newFixedThreadPool(4);
            this.repositoryManager = new RepositoryManager(executorService);
            
            // Restart health check endpoint with new config
            startHealthCheckEndpoint();
            
            getLogger().info("Configuration reloaded successfully");
            sender.sendMessage(ChatColor.GREEN + "Configuration reloaded successfully!");
            sender.sendMessage(ChatColor.GRAY + "Updated settings: include_only=" + configManager.getIncludeOnlyMode() + 
                    ", json_format=" + configManager.getConsoleJsonFormat() + 
                    ", health_check=" + configManager.isHealthCheckEnabled());
            
        } catch (IOException e) {
            getLogger().severe("Failed to reload configuration: " + e.getMessage());
            e.printStackTrace();
            sender.sendMessage(ChatColor.RED + "Failed to reload configuration: " + e.getMessage());
        }
    }
    
    /**
     * Handle the /updateplugins metrics command
     */
    private void handleMetricsCommand(CommandSender sender, String[] args) {
        boolean prometheus = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--prometheus") || args[i].equalsIgnoreCase("-p")) {
                prometheus = true;
                break;
            }
        }
        
        Map<String, Long> metrics = repositoryManager.getMetrics();
        
        if (prometheus) {
            String prometheusOutput = repositoryManager.getMetricsPrometheus();
            sender.sendMessage(ChatColor.GOLD + "=== Metrics (Prometheus format) ===");
            for (String line : prometheusOutput.split("\n")) {
                if (!line.startsWith("#")) {
                    sender.sendMessage(ChatColor.WHITE + line);
                }
            }
        } else {
            sender.sendMessage(ChatColor.GOLD + "=== Hanafubuki Plugin Updater Metrics ===");
            sender.sendMessage(ChatColor.YELLOW + "  Checks total: " + ChatColor.WHITE + metrics.get("checks_total"));
            sender.sendMessage(ChatColor.YELLOW + "  Updates found: " + ChatColor.WHITE + metrics.get("updates_found"));
            sender.sendMessage(ChatColor.YELLOW + "  Downloads successful: " + ChatColor.WHITE + metrics.get("downloads_success"));
            sender.sendMessage(ChatColor.YELLOW + "  Downloads failed: " + ChatColor.WHITE + metrics.get("downloads_failed"));
            sender.sendMessage(ChatColor.YELLOW + "  Errors: " + ChatColor.WHITE + metrics.get("errors"));
            sender.sendMessage(ChatColor.YELLOW + "  Plugins tracked: " + ChatColor.WHITE + repositoryManager.getPluginsTracked());
        }
    }
    
    /**
     * Start the health check HTTP endpoint if enabled in config
     */
    private void startHealthCheckEndpoint() {
        // Stop existing server if running
        if (healthCheckServer != null) {
            healthCheckServer.stop(0);
            healthCheckServer = null;
        }
        
        if (!configManager.isHealthCheckEnabled()) {
            getLogger().info("Health check endpoint disabled in config");
            return;
        }
        
        int port = configManager.getHealthCheckPort();
        try {
            healthCheckServer = HttpServer.create(new InetSocketAddress(port), 0);
            healthCheckServer.createContext("/health", new HealthCheckHandler());
            healthCheckServer.setExecutor(executorService);
            healthCheckServer.start();
            getLogger().info("Health check endpoint started on port " + port);
        } catch (IOException e) {
            getLogger().severe("Failed to start health check endpoint on port " + port + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * HTTP handler for health check endpoint
     */
    private class HealthCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                return;
            }
            
            String response = String.format(
                "{\"status\":\"UP\",\"version\":\"%s\",\"last_check\":%d,\"plugins_tracked\":%d}",
                getPluginMeta().getVersion(),
                repositoryManager.getLastCheckTimestamp(),
                repositoryManager.getPluginsTracked()
            );
            
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }
    
    /**
     * Run auto-discovery on startup (async)
     */
    private void runAutoDiscoveryOnStartup() {
        CompletableFuture.runAsync(() -> {
            try {
                getLogger().info("Running auto-discovery on startup...");
                List<ConfigManager.DiscoveredPlugin> discovered = configManager.discoverUnknownPlugins();
                
                if (discovered.isEmpty()) {
                    getLogger().info("Auto-discovery complete: no unknown plugins found");
                    return;
                }
                
                int added = 0;
                for (ConfigManager.DiscoveredPlugin dp : discovered) {
                    if (configManager.addDiscoveredPlugin(
                            dp.name(), dp.version(), dp.source(),
                            dp.projectId(), dp.repo(), dp.hangarSlug(),
                            dp.spigotmcResourceId(), dp.spigotmcResourceName(),
                            dp.localPath(), dp.customUrl())) {
                        added++;
                    }
                }
                
                getLogger().info("Auto-discovery complete: added " + added + " of " + discovered.size() + " plugins to config.yml");
                if (added > 0) {
                    getLogger().info("Restart the server to apply plugin updates for newly discovered plugins");
                }
                
            } catch (Exception e) {
                getLogger().severe("Error during auto-discovery on startup: " + e.getMessage());
                e.printStackTrace();
            }
        }, executorService);
    }

    /**
     * Handle the /updateplugins discover command
     * Lists discovered plugins not in config and optionally adds them
     * Usage:
     *   /updateplugins discover              - list unknown plugins
     *   /updateplugins discover add          - add all unknown plugins to config
     *   /updateplugins discover add <name>   - add specific plugin to config
     *   /updateplugins discover --auto       - run auto-discovery now (even if disabled in config)
     */
    private void handleDiscoverCommand(CommandSender sender, String[] args) {
        boolean autoRun = false;
        boolean shouldAdd = false;
        String specificPlugin = null;
        
        // Parse arguments
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--auto")) {
                autoRun = true;
            } else if (args[i].equalsIgnoreCase("add")) {
                shouldAdd = true;
                // Check if next argument is a plugin name (not a flag)
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    specificPlugin = args[i + 1];
                    i++; // Skip the plugin name
                }
            }
        }
        
        // Capture for lambda (must be effectively final)
        final String targetPlugin = specificPlugin;
        final boolean addMode = shouldAdd;
        final boolean autoMode = autoRun;
        
        // Check if auto-discovery is enabled (unless --auto flag is used)
        if (!autoMode && !configManager.getAutoDiscover()) {
            sender.sendMessage(ChatColor.RED + "Auto-discovery is disabled. Enable 'global.auto_discover' in config.yml to use this command, or use --auto flag.");
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            try {
                List<ConfigManager.DiscoveredPlugin> discovered = configManager.discoverUnknownPlugins();
                
                if (discovered.isEmpty()) {
                    sender.sendMessage(ChatColor.GREEN + "No unknown plugins found - all installed plugins are already configured!");
                    return;
                }
                
                // Filter for specific plugin if requested
                if (targetPlugin != null) {
                    discovered = discovered.stream()
                        .filter(dp -> dp.name().equalsIgnoreCase(targetPlugin))
                        .toList();
                    if (discovered.isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "Plugin '" + targetPlugin + "' not found among unknown plugins.");
                        return;
                    }
                }
                
                if (!addMode) {
                    // List mode
                    sender.sendMessage(ChatColor.GOLD + "=== Discovered " + discovered.size() + " unknown plugin(s) ===");
                    for (ConfigManager.DiscoveredPlugin dp : discovered) {
                        sender.sendMessage(ChatColor.YELLOW + "  - " + dp.name() + " v" + dp.version() + " (source: " + dp.source() + ")");
                    }
                    sender.sendMessage(ChatColor.AQUA + "Use /updateplugins discover add to add all to config.yml");
                    sender.sendMessage(ChatColor.AQUA + "Use /updateplugins discover add <PluginName> to add a specific plugin");
                    sender.sendMessage(ChatColor.AQUA + "Use /updateplugins discover --auto to run auto-discovery now");
                    return;
                }
                
                // Add mode
                int added = 0;
                for (ConfigManager.DiscoveredPlugin dp : discovered) {
                    if (configManager.addDiscoveredPlugin(
                            dp.name(), dp.version(), dp.source(),
                            dp.projectId(), dp.repo(), dp.hangarSlug(),
                            dp.spigotmcResourceId(), dp.spigotmcResourceName(),
                            dp.localPath(), dp.customUrl())) {
                        added++;
                        sender.sendMessage(ChatColor.GREEN + "Added: " + dp.name() + " v" + dp.version() + " (" + dp.source() + ")");
                    } else {
                        sender.sendMessage(ChatColor.RED + "Failed to add: " + dp.name());
                    }
                }
                
                sender.sendMessage(ChatColor.GOLD + "Added " + added + " of " + discovered.size() + " plugins to config.yml");
                
                // Provide guidance for incomplete configurations
                boolean hasIncomplete = discovered.stream().anyMatch(dp -> {
                    if ("github".equals(dp.source()) && (dp.repo() == null || dp.repo().isEmpty())) return true;
                    if ("hangar".equals(dp.source()) && (dp.hangarSlug() == null || dp.hangarSlug().isEmpty())) return true;
                    if ("spigotmc".equals(dp.source()) && (dp.spigotmcResourceId() == null || dp.spigotmcResourceId().isEmpty()) && 
                        (dp.spigotmcResourceName() == null || dp.spigotmcResourceName().isEmpty())) return true;
                    if ("local".equals(dp.source()) && (dp.localPath() == null || dp.localPath().isEmpty())) return true;
                    if ("custom".equals(dp.source()) && (dp.customUrl() == null || dp.customUrl().isEmpty())) return true;
                    return false;
                });
                
                if (hasIncomplete) {
                    sender.sendMessage(ChatColor.YELLOW + "Note: Some plugins have incomplete configuration. Edit config.yml to add missing fields:");
                    sender.sendMessage(ChatColor.YELLOW + "  - GitHub plugins need 'repo' (owner/repo)");
                    sender.sendMessage(ChatColor.YELLOW + "  - Hangar plugins need 'project_slug' (owner/slug)");
                    sender.sendMessage(ChatColor.YELLOW + "  - SpigotMC plugins need 'resource_id' or 'resource_name'");
                    sender.sendMessage(ChatColor.YELLOW + "  - Local plugins need 'path'");
                    sender.sendMessage(ChatColor.YELLOW + "  - Custom plugins need 'url'");
                }
                
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
     * Try to extract Hangar slug from plugin.yml
     */
    private String extractHangarSlug(File jarFile) {
        if (jarFile == null || !jarFile.exists()) {
            return null;
        }
        
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
            var entry = jar.getEntry("plugin.yml");
            if (entry != null) {
                try (var is = jar.getInputStream(entry);
                     var reader = new java.io.InputStreamReader(is)) {
                    var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
                    
                    // Check for website URL that might be Hangar
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
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
    
    /**
     * Try to extract SpigotMC resource ID from plugin.yml
     */
    private String extractSpigotMCResourceId(File jarFile) {
        if (jarFile == null || !jarFile.exists()) {
            return null;
        }
        
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
            var entry = jar.getEntry("plugin.yml");
            if (entry != null) {
                try (var is = jar.getInputStream(entry);
                     var reader = new java.io.InputStreamReader(is)) {
                    var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
                    
                    // Check for explicit spigotmc.resource_id field
                    String resourceId = yaml.getString("spigotmc.resource_id");
                    if (resourceId != null && !resourceId.isEmpty()) {
                        try {
                            Integer.parseInt(resourceId);
                            return resourceId;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    
                    // Check website URL for SpigotMC resource pattern
                    String website = yaml.getString("website");
                    if (website != null && website.contains("spigotmc.org")) {
                        // SpigotMC URLs like: https://www.spigotmc.org/resources/name.12345/
                        // We can try to extract numeric ID from URL
                        try {
                            java.net.URL url = new java.net.URL(website);
                            String path = url.getPath();
                            // Look for pattern .12345/ or .12345
                            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\.(\\d+)(?:/|$)");
                            java.util.regex.Matcher matcher = pattern.matcher(path);
                            if (matcher.find()) {
                                return matcher.group(1);
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
                
                // Update based on source
                repositoryManager.updateFromSource(pluginName, pluginDetails);
                
            } catch (Exception e) {
                getLogger().severe("Failed to update " + pluginName + ": " + e.getMessage());
                e.printStackTrace();
            }
        }, executorService);
    }
}