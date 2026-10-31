package com.hanafubuki.updater.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.hanafubuki.updater.discovery.PluginDiscovery;

/**
 * Loads config.yml and writes the few things the plugin changes itself (plugin versions after
 * an update, discovered plugins).
 *
 * <p>All methods are synchronized: updates are written from worker threads while a reload may
 * run on the main thread. The YAML path separator is set to an unused character so plugin names
 * containing dots are handled as single keys. Comments in the file are kept on save.
 */
public final class ConfigManager {

    private static final char PATH_SEPARATOR = '\u001F';

    private final JavaPlugin plugin;
    private final File file;
    private final Logger logger;
    private YamlConfiguration yaml;
    private UpdaterSettings settings;
    private List<String> problems = List.of();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "config.yml");
        this.logger = plugin.getLogger();
    }

    /**
     * Reads config.yml (creating it from the default on first start).
     *
     * @return true if the file was read; false if it is not valid YAML, in which case the
     *         previous settings (or empty defaults on first load) stay active
     */
    public synchronized boolean load() {
        if (!file.exists()) {
            plugin.saveResource("config.yml", false);
        }
        YamlConfiguration fresh = new YamlConfiguration();
        fresh.options().pathSeparator(PATH_SEPARATOR);
        try {
            fresh.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            logger.severe("config.yml is not valid YAML, keeping the previous settings: " + e.getMessage());
            if (settings == null) {
                SettingsParser.Result empty = SettingsParser.parse(Map.of());
                settings = empty.settings();
                problems = List.of("config.yml could not be read: " + e.getMessage());
            }
            return false;
        }
        SettingsParser.Result result = SettingsParser.parse(toMap(fresh));
        this.yaml = fresh;
        this.settings = result.settings();
        this.problems = result.problems();
        for (String p : problems) {
            logger.warning("config.yml: " + p);
        }
        return true;
    }

    public synchronized UpdaterSettings settings() {
        return settings;
    }

    public synchronized List<String> problems() {
        return problems;
    }

    /** Writes {@code version} of a plugin entry after an update. */
    public synchronized void setPluginVersion(String pluginName, String version) {
        ConfigurationSection entry = findPluginSection(pluginName);
        if (entry == null) {
            return;
        }
        entry.set("version", version);
        save();
    }

    /**
     * Adds a discovered plugin to the plugins section.
     *
     * @return false if an entry with this name exists already or the suggestion has no source
     */
    public synchronized boolean addPlugin(PluginDiscovery.Suggestion suggestion) {
        if (!suggestion.isUsable() || yaml == null || findPluginSection(suggestion.name()) != null) {
            return false;
        }
        ConfigurationSection plugins = yaml.getConfigurationSection("plugins");
        if (plugins == null) {
            plugins = yaml.createSection("plugins");
        }
        ConfigurationSection entry = plugins.createSection(suggestion.name());
        entry.set("source", suggestion.source());
        suggestion.options().forEach(entry::set);
        entry.set("version", suggestion.version());
        entry.setInlineComments("source", List.of("added by auto-discovery: " + suggestion.reason()));
        return save();
    }

    private ConfigurationSection findPluginSection(String pluginName) {
        if (yaml == null) {
            return null;
        }
        ConfigurationSection plugins = yaml.getConfigurationSection("plugins");
        if (plugins == null) {
            return null;
        }
        for (String key : plugins.getKeys(false)) {
            if (key.equalsIgnoreCase(pluginName) && plugins.isConfigurationSection(key)) {
                return plugins.getConfigurationSection(key);
            }
        }
        return null;
    }

    private boolean save() {
        try {
            yaml.save(file);
            // Keep the parsed settings in sync with what was written.
            SettingsParser.Result result = SettingsParser.parse(toMap(yaml));
            settings = result.settings();
            problems = result.problems();
            return true;
        } catch (IOException e) {
            logger.severe("Could not save config.yml: " + e.getMessage());
            return false;
        }
    }

    /** Converts a Bukkit section tree into plain nested maps and lists. */
    static Map<String, Object> toMap(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : section.getValues(false).entrySet()) {
            out.put(e.getKey(), plain(e.getValue()));
        }
        return out;
    }

    private static Object plain(Object value) {
        if (value instanceof ConfigurationSection s) {
            return toMap(s);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) {
                out.add(plain(o));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), plain(v)));
            return out;
        }
        return value;
    }
}
