package com.hanafubuki.updater.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hanafubuki.updater.manager.Schedule;
import com.hanafubuki.updater.source.GitHubSource;
import com.hanafubuki.updater.source.HangarSource;
import com.hanafubuki.updater.source.ModrinthSource;
import com.hanafubuki.updater.source.SourceSettings;

/**
 * Parsed, validated contents of config.yml. Immutable; a reload creates a new instance.
 */
public record UpdaterSettings(
        General general,
        Modes modes,
        Schedule schedule,
        ModrinthSource.Settings modrinth,
        GitHubSource.Settings github,
        boolean githubAdvisories,
        HangarSource.Settings hangar,
        SourceSettings spigotmc,
        SourceSettings custom,
        Backups backups,
        DependencyMode dependencies,
        Notifications notifications,
        Style style,
        boolean consoleJson,
        Health health,
        Map<String, PluginEntry> plugins,
        Set<String> configuredNames) {

    /**
     * @param plugins         valid entries of the {@code plugins:} section (plus confirmed plugins.json
     *                        entries after {@link #withDetected})
     * @param configuredNames every name under {@code plugins:} in config.yml, also entries with errors,
     *                        case-insensitive; these always win over plugins.json
     */
    public UpdaterSettings {
    }

    /** True if config.yml has an entry with this name, valid or not (case-insensitive). */
    public boolean inConfig(String name) {
        return configuredNames.contains(name);
    }

    /**
     * @param minVersion        installed versions below this are not updated (null = no limit)
     * @param maxVersion        versions above this are never installed (null = no limit)
     * @param autoDetect        look for plugins missing from config.yml at startup and before every
     *                          scheduled check; found plugins wait in plugins.json for confirmation
     * @param parallelism       plugins checked at the same time
     * @param maxDownloadBytes  largest accepted plugin jar
     */
    public record General(String minVersion, String maxVersion, boolean autoDetect, int parallelism,
                          long maxDownloadBytes) {
    }

    /**
     * @param includeOnly true: only plugins in includeList; false: all configured plugins
     * @param includeList plugin names (case-insensitive)
     * @param excludeList plugin names that are never updated, in both modes
     */
    public record Modes(boolean includeOnly, Set<String> includeList, Set<String> excludeList) {
        public boolean isIncluded(String name) {
            return includeList.stream().anyMatch(n -> n.equalsIgnoreCase(name));
        }

        public boolean isExcluded(String name) {
            return excludeList.stream().anyMatch(n -> n.equalsIgnoreCase(name));
        }
    }

    /**
     * @param enabled keep a copy of the replaced jar before staging an update
     * @param keep    how many backups to keep per plugin (oldest are deleted)
     */
    public record Backups(boolean enabled, int keep) {
    }

    /** What to do when a new version's dependencies are not met. */
    public enum DependencyMode {
        OFF, WARN, BLOCK
    }

    /** Events that can be sent to Discord / webhooks. */
    public enum Event {
        /** An update was downloaded and staged. */
        UPDATED,
        /** A dry run found an update. */
        AVAILABLE,
        /** A check or download failed. */
        FAILED,
        /** A security advisory affects an installed plugin. */
        SECURITY,
        /** Summary after each run. */
        SUMMARY
    }

    /**
     * @param inGame         message players with hanafubuki.updater.notify
     * @param onJoin         remind those players on join while updates wait for a restart
     * @param discord        Discord webhook
     * @param webhook        generic JSON webhook
     * @param serverName     server name used in Discord and webhook messages
     */
    public record Notifications(boolean inGame, boolean onJoin, Discord discord, Webhook webhook, String serverName) {
    }

    /**
     * @param enabled   send to Discord
     * @param url       webhook URL (https://discord.com/api/webhooks/...)
     * @param username  name shown in Discord, may be null
     * @param avatarUrl avatar shown in Discord, may be null
     * @param mention   text prepended to messages, e.g. "<@&roleId>", may be null
     * @param events    which events are sent
     */
    public record Discord(boolean enabled, String url, String username, String avatarUrl, String mention, Set<Event> events) {
    }

    /**
     * @param enabled send to the webhook
     * @param url     endpoint receiving a JSON POST
     * @param headers extra headers (e.g. Authorization), values may be ${ENV_VAR}
     * @param events  which events are sent
     */
    public record Webhook(boolean enabled, String url, Map<String, String> headers, Set<Event> events) {
    }

    /**
     * MiniMessage styling for chat and console output.
     *
     * @param prefix  prefix before every message (full MiniMessage)
     * @param colors  named styles: primary, success, warning, error, muted, accent (MiniMessage opening tags)
     */
    public record Style(String prefix, Map<String, String> colors) {
    }

    /**
     * @param enabled     start the /health HTTP endpoint
     * @param bindAddress address to listen on (127.0.0.1 by default)
     * @param port        TCP port
     */
    public record Health(boolean enabled, String bindAddress, int port) {
    }

    /** Download timeout for a source id. */
    public Duration downloadTimeout(String source) {
        return switch (source) {
            case "modrinth" -> modrinth.source().downloadTimeout();
            case "github" -> github.source().downloadTimeout();
            case "hangar" -> hangar.source().downloadTimeout();
            case "spigotmc" -> spigotmc.downloadTimeout();
            case "custom" -> custom.downloadTimeout();
            default -> Duration.ofSeconds(60);
        };
    }

    /**
     * These settings with confirmed plugins from plugins.json added. config.yml entries come
     * first and win over a detected entry with the same name (case-insensitive).
     */
    public UpdaterSettings withDetected(Map<String, PluginEntry> detected) {
        if (detected == null || detected.isEmpty()) {
            return this;
        }
        java.util.TreeSet<String> taken = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        taken.addAll(plugins.keySet());
        taken.addAll(configuredNames);
        Map<String, PluginEntry> merged = new java.util.LinkedHashMap<>(plugins);
        detected.forEach((name, entry) -> {
            if (taken.add(name)) {
                merged.put(name, entry);
            }
        });
        return new UpdaterSettings(general, modes, schedule, modrinth, github, githubAdvisories, hangar, spigotmc,
                custom, backups, dependencies, notifications, style, consoleJson, health,
                java.util.Collections.unmodifiableMap(merged), configuredNames);
    }

    /** Plugin names in config order. */
    public List<String> pluginNames() {
        return List.copyOf(plugins.keySet());
    }
}
