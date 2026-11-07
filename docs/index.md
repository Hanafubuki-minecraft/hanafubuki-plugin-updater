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
2. With `global.auto_detect: true` (the default) the updater looks at every installed plugin one tick after
   startup and identifies its source (Modrinth hash of the jar, or the `website` in plugin.yml). Found plugins
   are written to `plugins.json` as **waiting for confirmation** and are **not updated** until you decide.
   Ops (`hanafubuki.updater.confirm`) get a message with a clickable `[review]`. Run `/updateplugins pending`
   (or open the menu → **Found plugins**), then `/updateplugins confirm <plugin>` or `confirm all`; `reject <plugin>` for the ones you
   don't want updated. Plugins without a source, or that you want to configure yourself, go into config.yml:

   ```yaml
   plugins:
     LuckPerms:
       source: modrinth
     ViaVersion:
       source: hangar
       project_slug: ViaVersion
   ```

3. `/updateplugins reload` after editing config.yml, then `/updateplugins check` to see what would be updated.
4. `/updateplugins update` downloads the updates. Ops get "Plugin X updated, try restarting".
5. Restart the server. Optional: set `schedule.mode: daily` for automatic runs.

## Found plugins

Automatic detection (`global.auto_detect`, default on) runs one tick after startup, before every scheduled
check and after `/updateplugins reload`; `/updateplugins discover` runs it by hand. It looks only at plugins
that are neither in config.yml `plugins:` nor in `modes.exclude_list`, and **never updates anything on its
own**: a found plugin is updated only after an op confirms it. Every name under `plugins:` counts, even an
entry with errors. When new plugins wait for confirmation, players with `hanafubuki.updater.confirm` get a
message with a clickable `[review]` (if `notifications.in_game` is on), and on join (if
`notifications.on_join` is on) they are told how many still wait. A confirmation belongs to the installed
plugin: if the plugin is uninstalled, its entry is removed and a jar with the same name installed later has to
be confirmed again (only rejections are kept). Details:
[configuration.md → Found plugins](configuration.md#found-plugins-pluginsjson).

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
| `/updateplugins pending` | Found plugins: waiting (source, options, how it was found; clickable `[confirm]` / `[reject]` for players), without a source (`[hide]`), confirmed, rejected | `hanafubuki.updater.confirm` |
| `/updateplugins confirm <plugin\|all>` | Update a found plugin from now on (a waiting or rejected one), or every waiting one | `hanafubuki.updater.confirm` |
| `/updateplugins reject <plugin>` | Stop updating a found plugin, or hide one without a source; can be confirmed later | `hanafubuki.updater.confirm` |
| `/updateplugins discover` | Search for new plugins now; also looks up plugins without a source again | `hanafubuki.updater.discover` |
| `/updateplugins notify test` | Send a test Discord / webhook message | `hanafubuki.updater.notifytest` |
| `/updateplugins gui` | Open the menu (players only) | `hanafubuki.updater.gui` |
| `/updateplugins reload` | Reload config.yml and list its problems | `hanafubuki.updater.reload` |
| `/updateplugins help` | List the sub-commands you may use | `hanafubuki.updater.use` |

Only one run can be active. Plugin names in commands are case-insensitive; tab completion offers sub-commands
you have permission for and, only if you have the sub-command's permission (`update`: `update` or `check`),
its arguments: tracked plugin names (config.yml entries and confirmed found plugins); `confirm` and `reject`
complete the found plugins they apply to. `reload`, `rollback` and `unstage` are refused while a run is
active. `rollback <plugin> <version>` accepts versions that contain spaces.

Found plugins without a source (`unidentified`) cannot be confirmed: add a config.yml entry for them instead.
`confirm` warns when the plugin is in `modes.exclude_list`, or when `modes.include_only` is on and the plugin
is not in `include_list`, because then it is still not updated. `discover add` from 1.5.x was removed; it now
only prints a hint.

## Permissions

| Node | Grants |
|---|---|
| `hanafubuki.updater.*` | all nodes below |
| `hanafubuki.updater.use` | `/updateplugins` at all |
| `hanafubuki.updater.check` | `check`, `--dry-run`, `update --dry-run`; GUI left click and "Check all" |
| `hanafubuki.updater.update` | `update`, plain `/updateplugins`; GUI shift + left click and "Update all" |
| `hanafubuki.updater.stop` | `stop`; GUI "Stop" |
| `hanafubuki.updater.status` | `status`; GUI "Status" button and its details |
| `hanafubuki.updater.stats` | `stats`; statistics lines in the GUI plugin items |
| `hanafubuki.updater.metrics` | `metrics` |
| `hanafubuki.updater.rollback` | `backups`, `rollback`, `unstage`; GUI right click and backups view |
| `hanafubuki.updater.discover` | `discover`; GUI "Search now" |
| `hanafubuki.updater.confirm` | `pending`, `confirm`, `reject`; GUI found plugins view (together with `gui`); in-game "new plugin found" messages and the on-join reminder about plugins waiting for confirmation |
| `hanafubuki.updater.reload` | `reload`; GUI "Reload config" |
| `hanafubuki.updater.notifytest` | `notify test` |
| `hanafubuki.updater.gui` | `gui` |
| `hanafubuki.updater.notify` | receive in-game update and security messages and the on-join reminders about staged updates and advisories |

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

`/updateplugins gui` opens a 6-row menu with one item per tracked plugin (config.yml entries and confirmed
found plugins, 45 per page):

| Colour | State |
|---|---|
| orange | the last run found a security advisory |
| purple | rolled back, updates paused |
| yellow | an update waits for a restart |
| light blue | update available (from the last check) |
| lime | up to date |
| red | the last check failed or was blocked |
| gray | not installed, or not checked since the server started |

The lore shows source, where the entry comes from (`config.yml` or `plugins.json`) and installed version; with
`hanafubuki.updater.stats` also latest seen version, checks/updates/failures and the last check.

| Click on a plugin | Action | Permission |
|---|---|---|
| left | check this plugin | `check` |
| shift + left | update this plugin | `update` |
| right (or shift + right) | open its backups | `rollback` |

Bottom row: previous page, **Found plugins** (a bell with the count when plugins wait for confirmation),
**Check all**, **Update all**, **Status** (needs `status`; hover for running state, schedule, next and last
run, staged count), **Stop**, **Reload config**, next page.

Found plugins view (needs `gui` and `confirm`): one item per plugins.json entry, those that need a decision
first.

| Colour | State |
|---|---|
| orange | waiting for confirmation, not updated |
| lime | confirmed, updated |
| red | rejected, not updated |
| gray | no source found |

The lore shows installed version, source and options, how it was found, when it was found and who decided.
Left click confirms, right click rejects (hides a plugin without a source). **Confirm all waiting** confirms
every waiting plugin, **Back** returns to the main view, **Search now** runs `discover` (needs `discover`);
arrows page through the list.

Backups view: one chest per backup (version, date, size); a click stages exactly that backup file for the next
restart (like `rollback`, also when several backups share a version). **Remove staged update** runs `unstage`; **Back** returns to the main view.

Every action runs the same code and checks the same permission as the matching command. Items cannot be taken
out of the menu. Only plain mouse clicks act (left, right, shift + left, shift + right); Q, number keys, F,
middle and double clicks do nothing. Clicks are handled one tick after the click event (Bukkit does not allow
opening or closing inventories inside it), which also prevents an action from running twice.

## Files

| Path | Contents |
|---|---|
| `plugins/HanafubukiPluginUpdater/config.yml` | your configuration. Created on first start, never written by the plugin afterwards. Its `plugins:` entries always win over plugins.json |
| `plugins/HanafubukiPluginUpdater/plugins.json` | plugins found automatically and your decisions (`pending`, `confirmed`, `rejected`, `unidentified`). Written only by the updater: do not edit, use the commands or the menu |
| `plugins/HanafubukiPluginUpdater/state.json` | last run, per-plugin statistics, staged and held versions |
| `plugins/HanafubukiPluginUpdater/backups/<Plugin>/<version>__<epochMillis>.jar` | backups |
| `plugins/update/` (Bukkit `settings.update-folder`) | staged updates, applied by Paper on restart |

## Troubleshooting

| Message | Meaning |
|---|---|
| `GitHub repository X was not found. If it is private, set github.token ...` | The repository name is wrong, or it is private. Private repositories need `github.token` with read access. |
| `GitHub repository X has no published release` | The repository exists but has no release (drafts and, by default, pre-releases do not count). Publish a release with the jar attached. |
| `GitHub API rate limit reached ...` | Without a token GitHub allows 60 requests per hour per IP. Set `github.token`. |
| `The installed jar is not a file published on Modrinth` | The jar was downloaded from another site (SpigotMC, Hangar, GitHub) or modified. Set `project_id` to the slug from the plugin's `modrinth.com/plugin/<slug>` page. After the first update from Modrinth the hash is known and `project_id` is optional. |
| `GitHub rejected github.token (HTTP 401 ...)` | The token is mistyped, expired or revoked. Create a new one, put it in `github.token`, run `/updateplugins reload`. |
| `Modrinth has only beta versions of ...` | The project publishes no releases for your loader/version (Geyser, for example). Add `version_types: [release, beta]` to that plugin, or leave it to another updater. |
| `global.auto_update_config: removed in 1.6.0, delete this line ...` (also `auto_discover`, `auto_discover_on_startup`) | The key is gone in 1.6.0. Delete the line; it has no effect. Automatic detection is `global.auto_detect`. |
| `No source found for X` | The plugin's jar is not on Modrinth and its plugin.yml has no usable `website`. Add an entry for it to config.yml, or hide it with `/updateplugins reject X`. |
| `Could not read plugins.json ...` | The file is not valid JSON (edited by hand or damaged). It was moved to `plugins.json.broken` and detection starts from scratch; earlier decisions have to be made again. A single broken entry only loses that entry. |

## Links

- Repository: <https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater>
- Modrinth API: <https://docs.modrinth.com>
- GitHub REST API: <https://docs.github.com/en/rest>
- MiniMessage format: <https://docs.advntr.dev/minimessage/format.html>
