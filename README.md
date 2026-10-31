<div align="center">

# HanafubukiPluginUpdater

**Keeps every plugin on your Paper server up to date — safely, in the background, without touching running jars.**

[![Build](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/actions/workflows/release.yml/badge.svg)](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/actions/workflows/release.yml)
[![Release](https://img.shields.io/github/v/release/Hanafubuki-minecraft/hanafubuki-plugin-updater)](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest)
[![License: GPL v3](https://img.shields.io/github/license/Hanafubuki-minecraft/hanafubuki-plugin-updater)](LICENSE)
![Paper](https://img.shields.io/badge/Paper-1.21.9-blue)
![Java](https://img.shields.io/badge/Java-21-orange)

English · [Русский](README.ru.md)

</div>

---

Made for the **Hanafubuki** server, but works on any Paper server.
Run `/updateplugins` — new versions are downloaded into `plugins/update/`, and Paper swaps them in on the next restart.

## Features

- 🟢 **Modrinth first** — the installed jar is identified by its SHA-512 hash, not by searching names, so a look-alike project can't sneak in
- 🐙 **GitHub releases** — latest release, asset picked by a glob pattern (`plugin-*.jar`)
- 📦 **More sources** — [Hangar](https://hangar.papermc.io), SpigotMC (via Spiget), a local / network folder, or any JSON REST API with JSONPath
- 🛡️ **Safe install** — downloads to `plugins/update/<same name>.jar.part`, checks the hash and the `name` in `plugin.yml`, then moves it atomically. Running jars are never overwritten
- ⚡ **Fully async** — all network I/O runs off the main thread, plugins are checked in parallel; retries with backoff on failures
- 🎯 **Version filters** — global `min_version` / `max_version`, overridable per plugin; Modrinth is filtered by loader and game version
- 📋 **Two modes** — update only `include_list`, or everything except `exclude_list` (exclusions work in both)
- 🔍 **Auto-discovery** — finds installed plugins missing from the config and adds them for you
- 🧪 **Dry run** — see what would be updated without downloading anything
- 📢 **Ops get notified** — "Plugin X updated, try restarting" once an update is queued
- 📊 **Observability** — `/updateplugins metrics` (also Prometheus format), optional JSON log lines and an HTTP `/health` endpoint

## Installation

1. Download the latest `.jar` from [Releases](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest).
2. Put it into `plugins/`.
3. Restart the server — `plugins/HanafubukiPluginUpdater/config.yml` is created.
4. Add your plugins to the `plugins:` section (or run `/updateplugins discover --auto`).
5. Run `/updateplugins`, then restart when ops are notified.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/updateplugins` | Check all plugins and download updates | `hanafubuki.updater.update` (op) |
| `/updateplugins dry-run` | Show what would be updated, download nothing (also `--dry-run`, `-d`) | `hanafubuki.updater.update` |
| `/updateplugins reload` | Reload `config.yml` | `hanafubuki.updater.update` |
| `/updateplugins discover` | List installed plugins that are not in the config | `hanafubuki.updater.update` |
| `/updateplugins discover add [plugin]` | Add all (or one) discovered plugins to the config | `hanafubuki.updater.update` |
| `/updateplugins discover --auto` | Run auto-discovery now | `hanafubuki.updater.update` |
| `/updateplugins metrics [--prometheus]` | Checks, updates, downloads and errors since start | `hanafubuki.updater.update` |

Tab completion works for subcommands and discovered plugin names.

## Configuration

`plugins/HanafubukiPluginUpdater/config.yml`, short version:

```yaml
global:
  min_version: "1.0.0"
  max_version: "99.99.99"
  auto_discover: false
  auto_discover_on_startup: false
  auto_update_config: true     # write the new version back after an update

modes:
  include_only: false          # true = only plugins from include_list
  include_list: []
  exclude_list: []             # skipped in both modes

modrinth:
  # loaders: ["paper"]
  # game_versions: ["1.21.9"]

github:
  # token: "ghp_..."           # optional, raises the API rate limit

plugins:
  "LuckPerms":
    source: "modrinth"
    version: "5.4.120"
  "Floodgate":
    source: "github"
    version: "2.4.0"
    repo: "GeyserMC/Floodgate"
    asset_pattern: "floodgate-spigot.jar"
```

### Sources

| `source` | Required fields | Optional | Verification |
|---|---|---|---|
| `modrinth` | `version` | `project_id` (ID or slug) | SHA-512 + `plugin.yml` name |
| `github` | `version`, `repo` (`owner/repo`) | `asset_pattern` | `plugin.yml` name |
| `hangar` | `version`, `project_slug` (`owner/slug`) | — | SHA-256 + `plugin.yml` name |
| `spigotmc` | `version`, `resource_id` or `resource_name` | — | `plugin.yml` name (Spiget has no checksums) |
| `local` | `version`, `path` (folder or file) | `pattern`, `checksum` | SHA-256 / `.sha256`, `.sha512` sidecar + `plugin.yml` name |
| `custom` | `version`, `url` | `version_path`, `download_url_path`, `checksum_path`, `headers`, `auth` | SHA-256 (if provided) + `plugin.yml` name |

Every plugin entry can also set its own `min_version` / `max_version`.

Full reference with every option and default: [`docs/config.example.yml`](docs/config.example.yml) and [`docs/configuration.md`](docs/configuration.md).

## Compatibility

| Platform | Status |
|---|---|
| Paper 1.21.9 | ✅ Supported |
| Purpur, Pufferfish and other Paper forks | ✅ Should work |
| Geyser / Floodgate, ViaVersion | ✅ Supported (and can be updated by it) |
| Spigot / CraftBukkit | ❌ Not supported |

## Documentation

- [Architecture](docs/architecture.md) — components, data flow, threading
- [Configuration](docs/configuration.md) — every option, modes and version filters
- [API](docs/api.md) — how Modrinth and GitHub are queried
- [Security](docs/security.md) — threat model and verification
- [Development](docs/development.md) — build, tests, release flow

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
version=1.2.2
```

Bump it and push to `master` → GitHub Actions builds the jar and publishes release `v<version>` with auto-generated notes.

## License

[GNU General Public License v3.0](LICENSE) © ByteMe6

<div align="center">

*Part of the [Hanafubuki](https://github.com/Hanafubuki-minecraft) ecosystem*

</div>
