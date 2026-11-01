package com.hanafubuki.updater.source;

import com.hanafubuki.updater.config.PluginEntry;

/**
 * Everything a source needs to look up an update for one plugin.
 *
 * @param entry            the plugin's config entry
 * @param installed        the plugin as currently loaded by the server
 * @param minecraftVersion server Minecraft version, e.g. "1.21.9"
 */
public record UpdateRequest(PluginEntry entry, InstalledPlugin installed, String minecraftVersion) {

    public String pluginName() {
        return installed.name();
    }
}
