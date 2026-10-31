package com.hanafubuki.updater;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tab completer for /updateplugins command
 */
public class UpdatePluginsTabCompleter implements TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "dry-run", "reload", "discover", "metrics", "--dry-run"
    );

    private static final List<String> DISCOVER_SUBCOMMANDS = Arrays.asList(
        "add", "--auto"
    );

    private static final List<String> METRICS_SUBCOMMANDS = Arrays.asList(
        "--prometheus"
    );

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("updateplugins")) {
            return Collections.emptyList();
        }

        // No permission? No completions
        if (!sender.hasPermission("hanafubuki.updater.update")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterMatches(SUBCOMMANDS, args[0]);
        }

        if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            if (subCmd.equals("discover")) {
                return filterMatches(DISCOVER_SUBCOMMANDS, args[1]);
            }
            if (subCmd.equals("metrics")) {
                return filterMatches(METRICS_SUBCOMMANDS, args[1]);
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("discover") && args[1].equalsIgnoreCase("add")) {
            // Suggest plugin names not in config
            return getUnknownPluginNames(args[2]);
        }

        return Collections.emptyList();
    }

    private List<String> filterMatches(List<String> options, String input) {
        if (input == null || input.isEmpty()) {
            return new ArrayList<>(options);
        }
        String lower = input.toLowerCase();
        return options.stream()
            .filter(opt -> opt.toLowerCase().startsWith(lower))
            .collect(Collectors.toList());
    }

    private List<String> getUnknownPluginNames(String prefix) {
        List<String> result = new ArrayList<>();
        try {
            // Get configured plugin names
            java.util.Set<String> configured = new java.util.HashSet<>();
            try {
                var configManager = com.hanafubuki.updater.PluginUpdater.getPlugin(com.hanafubuki.updater.PluginUpdater.class);
                var cmField = configManager.getClass().getDeclaredField("configManager");
                cmField.setAccessible(true);
                var configManagerInstance = cmField.get(configManager);
                if (configManagerInstance != null) {
                    var method = configManagerInstance.getClass().getMethod("getConfiguredPluginNames");
                    configured = (java.util.Set<String>) method.invoke(configManagerInstance);
                }
            } catch (Exception ignored) {
                // Fallback: empty set
            }

            for (Plugin plugin : org.bukkit.Bukkit.getPluginManager().getPlugins()) {
                String name = plugin.getName();
                if (!configured.contains(name) && name.toLowerCase().startsWith(prefix.toLowerCase())) {
                    result.add(name);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}