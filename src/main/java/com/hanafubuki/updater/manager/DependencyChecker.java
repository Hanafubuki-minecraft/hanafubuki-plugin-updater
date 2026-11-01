package com.hanafubuki.updater.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

import com.hanafubuki.updater.util.PluginDescriptor;
import com.hanafubuki.updater.util.VersionUtils;

/**
 * Checks whether a downloaded plugin version can run on this server:
 * <ul>
 *   <li>every hard dependency ({@code depend} in plugin.yml, required server dependencies in
 *       paper-plugin.yml) is installed,</li>
 *   <li>its {@code api-version} is not newer than the server's Minecraft version.</li>
 * </ul>
 */
public final class DependencyChecker {

    private DependencyChecker() {
    }

    /**
     * @param descriptor       descriptor of the new jar
     * @param installedPlugins names of all loaded plugins
     * @param minecraftVersion server version, e.g. "1.21.9"
     * @return problems; empty if the new version can be installed
     */
    public static List<String> check(PluginDescriptor descriptor, Collection<String> installedPlugins, String minecraftVersion) {
        List<String> problems = new ArrayList<>();
        TreeSet<String> installed = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        installed.addAll(installedPlugins);
        List<String> missing = descriptor.depend().stream().filter(d -> !installed.contains(d)).toList();
        if (!missing.isEmpty()) {
            problems.add("needs plugins that are not installed: " + String.join(", ", missing));
        }
        String api = descriptor.apiVersion();
        if (api != null && !api.isBlank() && minecraftVersion != null && VersionUtils.compare(api, minecraftVersion) > 0) {
            problems.add("requires api-version " + api + " but the server runs " + minecraftVersion);
        }
        return problems;
    }
}
