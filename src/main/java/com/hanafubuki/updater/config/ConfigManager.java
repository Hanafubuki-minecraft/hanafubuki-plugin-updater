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

/**
 * Loads config.yml. The file belongs to the user: the plugin only creates it from the default on
 * first start and never writes to it afterwards (found plugins go to plugins.json, versions to
 * state.json).
 *
 * <p>Methods are synchronized because the settings are read from worker threads while a reload
 * runs on the main thread. The YAML path separator is set to an unused character so plugin names
 * containing dots are handled as single keys.
 */
public final class ConfigManager {

    private static final char PATH_SEPARATOR = '\u001F';

    private final JavaPlugin plugin;
    private final File file;
    private final Logger logger;
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
