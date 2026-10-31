package com.hanafubuki.updater.util;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Utility class for JAR file operations
 */
public class JarUtils {

    /**
     * Extract plugin name from plugin.yml inside JAR
     * @param jarPath Path to the JAR file
     * @return Plugin name from plugin.yml or null if not found or error occurs
     */
    public static String getPluginNameFromJar(Path jarPath) {
        if (!Files.exists(jarPath)) {
            return null;
        }
        
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            var entry = jarFile.getEntry("plugin.yml");
            if (entry != null) {
                try (var is = jarFile.getInputStream(entry);
                     var reader = new InputStreamReader(is)) {
                    var yaml = YamlConfiguration.loadConfiguration(reader);
                    return yaml.getString("name");
                }
            }
        } catch (IOException e) {
            // Invalid JAR or read error - return null
            return null;
        }
        return null;
    }
}