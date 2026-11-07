package com.hanafubuki.updater;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import com.hanafubuki.updater.discovery.PluginDiscovery;
import com.hanafubuki.updater.source.InstalledPlugin;
import com.hanafubuki.updater.util.PluginJars;

/**
 * Reads the loaded plugins from Bukkit. Must be called on the server main thread; the result is
 * an immutable snapshot that worker threads can use safely.
 */
public final class ServerPlugins {

    private ServerPlugins() {
    }

    /** All loaded plugins that come from a jar file, keyed by name. */
    public static Map<String, InstalledPlugin> snapshot() {
        Map<String, InstalledPlugin> out = new LinkedHashMap<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            jarOf(p).ifPresent(jar -> out.put(p.getName(), new InstalledPlugin(p.getName(),
                    p.getPluginMeta().getVersion(), jar, p.getPluginMeta().getPluginDependencies())));
        }
        return out;
    }

    /** Loaded plugins that are not configured yet, as discovery candidates. */
    public static List<PluginDiscovery.Candidate> unconfigured(Set<String> configuredNames, String selfName) {
        TreeSet<String> configured = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        configured.addAll(configuredNames);
        List<PluginDiscovery.Candidate> out = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (configured.contains(p.getName()) || p.getName().equalsIgnoreCase(selfName)) {
                continue;
            }
            Optional<Path> jar = jarOf(p);
            if (jar.isEmpty()) {
                continue;
            }
            out.add(new PluginDiscovery.Candidate(p.getName(), p.getPluginMeta().getVersion(), jar.get(),
                    p.getPluginMeta().getWebsite()));
        }
        return out;
    }

    /** The jar a plugin was loaded from. */
    public static Optional<Path> jarOf(Plugin plugin) {
        try {
            URL location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return Optional.empty();
            }
            Path loaded = new File(location.toURI()).toPath();
            // Paper may run a remapped copy; updates must check and replace the original jar.
            return PluginJars.original(loaded, plugin.getDataFolder().toPath().getParent());
        } catch (URISyntaxException | SecurityException | NullPointerException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static Path updateFolder() {
        return Bukkit.getUpdateFolderFile().toPath();
    }

    public static String minecraftVersion() {
        return Bukkit.getMinecraftVersion();
    }
}
