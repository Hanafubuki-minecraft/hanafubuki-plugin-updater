package com.hanafubuki.updater;

import java.util.List;
import java.util.Map;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import com.hanafubuki.updater.console.Messages;
import com.hanafubuki.updater.manager.PluginResult;
import com.hanafubuki.updater.source.GitHubAdvisories;

/**
 * Reminds players with {@code hanafubuki.updater.notify} about found plugins that wait for
 * confirmation, updates that wait for a restart and open security advisories from the last run.
 */
final class JoinListener implements Listener {

    private final PluginUpdater plugin;

    JoinListener(PluginUpdater plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!plugin.settings().notifications().onJoin() || !player.hasPermission(Permissions.NOTIFY)) {
            return;
        }
        Messages m = plugin.messages();
        List<String> pending = plugin.registry().pendingNames();
        if (!pending.isEmpty()) {
            m.send(player, "<warning><count> new plugin(s) found, waiting for confirmation: <accent><list></accent> "
                            + "<click:run_command:'/updateplugins pending'><primary>[review]</primary></click>",
                    Messages.text("count", pending.size()), Messages.text("list", String.join(", ", pending)));
        }
        Map<String, String> staged = plugin.state().allStaged();
        if (!staged.isEmpty()) {
            m.send(player, "<warning><count> plugin update(s) wait for a restart: <accent><list>",
                    Messages.text("count", staged.size()), Messages.text("list", String.join(", ", staged.keySet())));
        }
        plugin.lastReport().ifPresent(report -> {
            for (PluginResult r : report.withAlerts()) {
                for (GitHubAdvisories.Alert a : r.alerts()) {
                    m.send(player, "<error>Security advisory (<severity>) for <plugin> <installed>: <summary>",
                            Messages.text("severity", a.severity()), Messages.text("plugin", r.plugin()),
                            Messages.text("installed", r.installedVersion()), Messages.text("summary", a.summary()));
                }
            }
        });
    }
}
