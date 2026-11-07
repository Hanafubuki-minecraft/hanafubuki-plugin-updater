<div align="center">

# HanafubukiPluginUpdater

**Keeps every plugin on your Paper server up to date — safely, in the background, without touching running jars.**

[![Build](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/actions/workflows/build.yml/badge.svg)](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Hanafubuki-minecraft/hanafubuki-plugin-updater)](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest)
[![License: GPL v3](https://img.shields.io/github/license/Hanafubuki-minecraft/hanafubuki-plugin-updater)](LICENSE)
![Paper](https://img.shields.io/badge/Paper-1.21.9-blue)
![Java](https://img.shields.io/badge/Java-21-orange)

English · [Русский](README.ru.md)

</div>

---

Made for the **Hanafubuki** server, but works on any Paper server.
New versions are downloaded into the Bukkit update folder (`plugins/update/`) under the installed jar's name,
and Paper swaps them in on the next restart.

## Features

- **Modrinth first** — the installed jar is identified by its SHA-512 hash, not by searching names, so a look-alike project can't sneak in
- **GitHub releases** — latest release or pre-releases, the jar chosen by a glob (`EssentialsX-2*.jar`) and never guessed, checksums from GitHub's digest, sidecar files or release notes, optional signature check, private repos with a token
- **More sources** — [Hangar](https://hangar.papermc.io) (channels, SHA-256), SpigotMC via Spiget, a local / network folder, or any JSON endpoint with JSON paths and auth
- **Safe install** — download to a temporary file, check the hash, the plugin `name` and its dependencies, then move it atomically into `plugins/update/`. Running jars are never overwritten
- **Backups and rollback** — the old jar is kept; `/updateplugins rollback` brings it back on the next restart
- **Security advisories** — GitHub advisories for every plugin with a `repo`, reported in console, chat and Discord
- **Schedule** — every N minutes, daily or weekly at a fixed time, on startup, or check-only
- **Notifications** — "Plugin X updated, try restarting" for ops, join reminders, Discord webhook, generic JSON webhook
- **GUI** — `/updateplugins gui`: plugin states at a glance, check / update / roll back with a click
- **Fine-grained permissions** — one node per sub-command, ready for LuckPerms
- **Fully async** — all network I/O off the main thread, plugins checked in parallel, retries with backoff, cancellable with `/updateplugins stop`
- **Observability** — statistics per plugin, `/updateplugins metrics`, JSON log lines, `/health` and Prometheus `/metrics` endpoint

## Installation

1. Download the latest `.jar` from [Releases](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest).
2. Put it into `plugins/` and restart the server — `plugins/HanafubukiPluginUpdater/config.yml` is created.
   It tracks no plugins yet.
3. Add your plugins to the `plugins:` section, or run `/updateplugins discover` and, with
   `global.auto_discover: true`, `/updateplugins discover add`.
4. `/updateplugins reload`, then `/updateplugins check` to see what would change.
5. `/updateplugins update`, and restart when ops are notified.

## Commands

`/updateplugins` — aliases `/pluginupdater`, `/hpu`.

| Command | Description | Permission |
|---|---|---|
| `/updateplugins` | Update all plugins (same as `update`) | `hanafubuki.updater.update` |
| `/updateplugins check [plugins…]` | Check for updates, download nothing (also `--dry-run`, `-d`, `dry-run`) | `hanafubuki.updater.check` |
| `/updateplugins update [all\|plugins…] [--dry-run]` | Download updates, applied on restart | `hanafubuki.updater.update` |
| `/updateplugins stop` | Cancel the running check | `hanafubuki.updater.stop` |
| `/updateplugins status` | Running check, waiting updates, schedule, last run | `hanafubuki.updater.status` |
| `/updateplugins stats [plugin]` | Checks, updates, failures, average update interval | `hanafubuki.updater.stats` |
| `/updateplugins metrics [--prometheus]` | Counters since the server started | `hanafubuki.updater.metrics` |
| `/updateplugins backups <plugin>` | List stored previous versions | `hanafubuki.updater.rollback` |
| `/updateplugins rollback <plugin> [version]` | Restore a previous version on the next restart | `hanafubuki.updater.rollback` |
| `/updateplugins unstage <plugin>` | Remove a downloaded update before restart, resume updates after a rollback | `hanafubuki.updater.rollback` |
| `/updateplugins discover [add] [plugin]` | Find installed plugins missing from the config (and add them) | `hanafubuki.updater.discover` |
| `/updateplugins notify test` | Send a test Discord / webhook message | `hanafubuki.updater.notifytest` |
| `/updateplugins gui` | Open the menu | `hanafubuki.updater.gui` |
| `/updateplugins reload` | Reload `config.yml` | `hanafubuki.updater.reload` |

Every sub-command also needs `hanafubuki.updater.use`. Tab completion offers only what you may use.
More detail and the GUI: [docs/index.md](docs/index.md).

## Permissions

All nodes default to op; `hanafubuki.updater.*` grants everything. Besides the nodes in the table above,
`hanafubuki.updater.notify` receives in-game update and security messages.

With LuckPerms:

```text
# moderators may check, but not install
/lp group moderator permission set hanafubuki.updater.use true
/lp group moderator permission set hanafubuki.updater.check true

# everything for one player
/lp user Steve permission set hanafubuki.updater.* true

# take one node away from opped admins
/lp group admin permission set hanafubuki.updater.update false
```

## Configuration

`plugins/HanafubukiPluginUpdater/config.yml`, short version:

```yaml
global:
  min_version: ""              # installed versions below this are left alone
  max_version: ""              # never install versions above this
  auto_update_config: true     # write the new version into the entry after an update
  parallelism: 4

modes:
  include_only: false          # true = only plugins from include_list
  include_list: []
  exclude_list: []             # skipped in both modes

schedule:
  mode: daily                  # off | interval | daily | weekly
  time: "04:30"
  check_only: false

github:
  token: "${GITHUB_TOKEN}"     # optional, read from the environment

notifications:
  discord:
    enabled: true
    webhook_url: "${DISCORD_WEBHOOK_URL}"

plugins:
  LuckPerms:
    source: modrinth
  ViaVersion:
    source: hangar
    project_slug: ViaVersion
    repo: ViaVersion/ViaVersion   # enables security advisories
  ProtocolLib:
    source: github
    repo: dmulloy2/ProtocolLib
    asset_pattern: "ProtocolLib.jar"
```

### Sources

| `source` | Required | Optional | Verification |
|---|---|---|---|
| `modrinth` | — | `project_id`, `loaders`, `game_versions`, `version_types` | SHA-512 |
| `github` | `repo` (`owner/repo`) | `asset_pattern`, `include_prereleases`, `signatures` | GitHub digest / sidecar / checksum list / release notes, if published; signatures |
| `hangar` | `project_slug` | `channel` | SHA-256 |
| `spigotmc` | `resource_id` or `resource_name` | — | none (Spiget has no checksums) |
| `local` | `path` (folder or file) | `pattern`, `checksum` | `.sha512` / `.sha256` / `.sha1` sidecar or `checksum` |
| `custom` | `url` | `version_path`, `download_url_path`, `checksum_path`, `checksum`, `headers`, `auth`, `allow_http` | checksum from the endpoint, if any |

Every download must also be a plugin jar with the same `name` as the installed plugin. Every entry can set
`min_version`, `max_version` and `repo` (GitHub security advisories). `version` is informational; the
installed version is read from the server.

Full reference: [docs/configuration.md](docs/configuration.md). Filled-in example with every source:
[docs/config.example.yml](docs/config.example.yml).

## Compatibility

| Platform | Status |
|---|---|
| Paper 1.21.9 | ✅ Supported |
| Purpur, Pufferfish and other Paper forks | ✅ Should work |
| Geyser / Floodgate, ViaVersion | ✅ Supported (and can be updated by it) |
| Spigot / CraftBukkit | ❌ Not supported (uses the Paper API) |

## Documentation

- [Overview](docs/index.md) — quick start, commands, permissions, GUI
- [Configuration](docs/configuration.md) — every option, sources, notification formats, health endpoint
- [Architecture](docs/architecture.md) — components, update run, threads, backups and rollback
- [Sources and HTTP](docs/api.md) — how each source is queried and verified
- [Security](docs/security.md) — threat model and verification
- [Development](docs/development.md) — build, tests, CI, release flow
- [Roadmap](docs/roadmap.md) — what's done, changelog, ideas

## Building

The project is built by GitHub Actions — you don't need to build it locally.
If you want to anyway: Java 21.

```bash
./gradlew build
# → build/libs/hanafubuki-plugin-updater-<version>.jar
```

## Releasing

The plugin version lives in a single place — [`gradle.properties`](gradle.properties):

```properties
version=1.5.2
```

Bump it and push to `master` → GitHub Actions builds the jar and publishes release `v<version>` with auto-generated notes.

## License

[GNU General Public License v3.0](LICENSE) © ByteMe6

<div align="center">

*Part of the [Hanafubuki](https://github.com/Hanafubuki-minecraft) ecosystem*

</div>
