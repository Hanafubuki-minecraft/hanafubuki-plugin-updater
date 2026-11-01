# Hanafubuki Plugin Updater – overview

A Paper 1.21.9 plugin (Java 21) that keeps the server's plugins up to date from Modrinth, GitHub releases,
Hangar, SpigotMC (Spiget), a local folder or any JSON endpoint. New versions are downloaded in the
background, verified and put into the Bukkit update folder (`plugins/update/` by default) under the installed
jar's file name. Paper swaps them in on the next restart; running jars are never touched.

| Document | For | Contents |
|---|---|---|
| [configuration.md](configuration.md) | admins | every option with default and range, plugin entries per source, notification and log formats, `/health` and `/metrics` |
| [config.example.yml](config.example.yml) | admins | filled-in config using every source |
| [architecture.md](architecture.md) | developers | packages, threads, how a run works, staging, state, backups, rollback |
| [api.md](api.md) | developers | how each source queries its API and verifies files, HTTP layer |
| [security.md](security.md) | everyone | threat model, mitigations, what is not protected |
| [development.md](development.md) | developers | build, tests, CI, releases, adding a source |
| [roadmap.md](roadmap.md) | everyone | what is done, changelog, future ideas |

## Quick start

1. Put the jar from the [latest release](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest)
   into `plugins/` and restart. `plugins/HanafubukiPluginUpdater/config.yml` is created; it tracks no plugins yet.
2. Run `/updateplugins discover` to see which installed plugins can be identified. Set
   `global.auto_discover: true` and run `/updateplugins discover add` to add them, or write entries by hand:

   ```yaml
   plugins:
     LuckPerms:
       source: modrinth
     ViaVersion:
       source: hangar
       project_slug: ViaVersion
   ```

3. `/updateplugins reload`, then `/updateplugins check` to see what would be updated.
4. `/updateplugins update` downloads the updates. Ops get "Plugin X updated, try restarting".
5. Restart the server. Optional: set `schedule.mode: daily` for automatic runs.

## Commands

`/updateplugins` (aliases `/pluginupdater`, `/hpu`). Every sub-command needs `hanafubuki.updater.use` **and**
its own node. All nodes default to op.

| Command | What it does | Permission |
|---|---|---|
| `/updateplugins` | Same as `update`: check all plugins and download updates (kept from 1.x) | `hanafubuki.updater.update` |
| `/updateplugins check [plugins…]` | Check for updates, download nothing | `hanafubuki.updater.check` |
| `/updateplugins --dry-run` (`-d`, `dry-run`) | Same as `check` | `hanafubuki.updater.check` |
| `/updateplugins update [all\|plugins…] [--dry-run]` | Download updates (all, or only the named plugins); applied on restart. `--dry-run` / `-d` makes it a check | `hanafubuki.updater.update` (`check` is enough with `--dry-run`) |
| `/updateplugins stop` | Cancel the running check | `hanafubuki.updater.stop` |
| `/updateplugins status` | Running or not, tracked plugins, schedule and next run, last run, updates waiting for a restart, notification targets, config problems | `hanafubuki.updater.status` |
| `/updateplugins stats [plugin]` | Checks, updates, failures, last update, average interval, last result | `hanafubuki.updater.stats` |
| `/updateplugins metrics [--prometheus]` | Counters since the server started (`-p` prints Prometheus lines) | `hanafubuki.updater.metrics` |
| `/updateplugins backups <plugin>` | List stored previous versions | `hanafubuki.updater.rollback` |
| `/updateplugins rollback <plugin> [version]` | Stage a backup (newest if no version) for the next restart and pause updates of that plugin | `hanafubuki.updater.rollback` |
| `/updateplugins unstage <plugin>` | Delete the plugin's staged file and resume its updates | `hanafubuki.updater.rollback` |
| `/updateplugins discover [plugin]` | List installed plugins missing from config.yml with a suggested source | `hanafubuki.updater.discover` |
| `/updateplugins discover add [plugin\|all]` | Add the identified ones to config.yml (needs `global.auto_discover: true`) | `hanafubuki.updater.discover` |
| `/updateplugins notify test` | Send a test Discord / webhook message | `hanafubuki.updater.notifytest` |
| `/updateplugins gui` | Open the menu (players only) | `hanafubuki.updater.gui` |
| `/updateplugins reload` | Reload config.yml and list its problems | `hanafubuki.updater.reload` |
| `/updateplugins help` | List the sub-commands you may use | `hanafubuki.updater.use` |

Only one run can be active. Plugin names in commands are case-insensitive; tab completion offers sub-commands
you have permission for and configured plugin names. `reload`, `rollback` and `unstage` are refused while a run
is active.

## Permissions

| Node | Grants |
|---|---|
| `hanafubuki.updater.*` | all nodes below |
| `hanafubuki.updater.use` | `/updateplugins` at all |
| `hanafubuki.updater.check` | `check`, `--dry-run`, `update --dry-run`; GUI left click and "Check all" |
| `hanafubuki.updater.update` | `update`, plain `/updateplugins`; GUI shift + left click and "Update all" |
| `hanafubuki.updater.stop` | `stop`; GUI "Stop" |
| `hanafubuki.updater.status` | `status`; GUI "Status" |
| `hanafubuki.updater.stats` | `stats` |
| `hanafubuki.updater.metrics` | `metrics` |
| `hanafubuki.updater.rollback` | `backups`, `rollback`, `unstage`; GUI right click and backups view |
| `hanafubuki.updater.discover` | `discover`, `discover add` |
| `hanafubuki.updater.reload` | `reload`; GUI "Reload config" |
| `hanafubuki.updater.notifytest` | `notify test` |
| `hanafubuki.updater.gui` | `gui` |
| `hanafubuki.updater.notify` | receive in-game update and security messages and on-join reminders |

Examples with LuckPerms:

```text
# moderators may check for updates, but not install them
/lp group moderator permission set hanafubuki.updater.use true
/lp group moderator permission set hanafubuki.updater.check true

# give one player everything
/lp user Steve permission set hanafubuki.updater.* true

# ops have everything by default; take one node away from the admin group
/lp group admin permission set hanafubuki.updater.update false
```

An explicit `false` overrides the op default, so the last example works for opped admins too.

## GUI

`/updateplugins gui` opens a 6-row menu with one item per configured plugin (45 per page):

| Colour | State |
|---|---|
| orange | the last run found a security advisory |
| purple | rolled back, updates paused |
| yellow | an update waits for a restart |
| light blue | update available (from the last check) |
| lime | up to date |
| red | the last check failed or was blocked |
| gray | not installed, or not checked since the server started |

The lore shows source, installed version, latest seen version, checks/updates/failures and the last check.

| Click on a plugin | Action | Permission |
|---|---|---|
| left | check this plugin | `check` |
| shift + left | update this plugin | `update` |
| right | open its backups | `rollback` |

Bottom row: previous page, **Check all**, **Update all**, **Status** (hover for running state, schedule, next
and last run, staged count), **Stop**, **Reload config**, next page.

Backups view: one chest per backup (version, date, size); a click stages that version for the next restart
(same as `rollback`). **Remove staged update** runs `unstage`; **Back** returns to the main view.

Every action checks the same permission as the matching command. Items cannot be taken out of the menu.

## Files

| Path | Contents |
|---|---|
| `plugins/HanafubukiPluginUpdater/config.yml` | configuration |
| `plugins/HanafubukiPluginUpdater/state.json` | last run, per-plugin statistics, staged and held versions |
| `plugins/HanafubukiPluginUpdater/backups/<Plugin>/<version>__<epochMillis>.jar` | backups |
| `plugins/update/` (Bukkit `settings.update-folder`) | staged updates, applied by Paper on restart |

## Links

- Repository: <https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater>
- Modrinth API: <https://docs.modrinth.com>
- GitHub REST API: <https://docs.github.com/en/rest>
- MiniMessage format: <https://docs.advntr.dev/minimessage/format.html>
