# Hanafubuki Plugin Updater

[![Build](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/actions/workflows/release.yml/badge.svg)](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/actions/workflows/release.yml)
[![Release](https://img.shields.io/github/v/release/Hanafubuki-minecraft/hanafubuki-plugin-updater)](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-red)](LICENSE)

**Automatic plugin updates for Paper servers from Modrinth and GitHub.**

English · [Русский](README.ru.md)

## How it works

- **Modrinth**: Computes SHA-512 of the installed JAR → `GET /version_file/{hash}` → project ID → `GET /project/{id}/version?loaders=paper&game_versions=["1.21.9"]` → verifies hash + `plugin.yml` name. No search-by-name, no spoofing.
- **GitHub**: `GET /repos/{owner}/{repo}/releases/latest` → matches asset by pattern → verifies `plugin.yml` name.
- **Safe install**: Downloads to `plugins/update/{name}.jar.part` → atomic move to `plugins/update/{name}.jar` → Paper swaps on next restart. Never overwrites running JARs.
- **Async**: All network I/O runs off the main thread via `CompletableFuture`.
- **Notify ops**: Broadcasts "Plugin X updated, try restarting" when updates are ready.

## Setup

### Requirements

- **Java 21**
- **Paper 1.21.9** (also works on Purpur, Pufferfish, Folia)
- Compatible with Geyser/Floodgate/ViaVersion

### Installation

1. Download the latest `.jar` from [Releases](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest)
2. Drop it into your server's `plugins/` folder
3. Start/restart the server
4. Edit `plugins/HanafubukiPluginUpdater/config.yml` (generated on first run from [config.example.yml](docs/config.example.yml))
5. Run `/updateplugins` to check for updates

## Configuration

The config is generated on first run from the built-in example.  
See [docs/config.example.yml](docs/config.example.yml) for the full commented reference.

**Key sections:**

```yaml
global:
  min_version: "1.0.0"
  max_version: "99.99.99"

modes:
  include_only: false          # true = only update plugins in include_list
  exclude_list: []             # plugins to skip (works in both modes)
  include_list: []             # plugins to update (when include_only: true)

modrinth:
  # timeout: 10
  # download_timeout: 60
  # loaders: ["paper"]
  # game_versions: ["1.21.9"]

github:
  # timeout: 10
  # download_timeout: 60
  # token: "ghp_..."           # optional, increases rate limit
  # default_branch: "main"

console:
  mini_message: true
  colors:
    info: "<green>"
    warning: "<yellow><bold>"
    error: "<red><italic>"
    success: "<aqua><bold>"
    header: "<gradient:gold:yellow>"

plugins:
  "WorldEdit":
    source: "modrinth"
    version: "7.3.12"
    # project_id: "worldedit"  # optional: explicit Modrinth ID/slug

  "Paper":
    source: "github"
    version: "1.21.9-R0.1"
    repo: "PaperMC/Paper"
    # asset_pattern: "paper-*.jar"
```

### Plugin entry fields

| Field | Modrinth | GitHub | Description |
|-------|:--------:|:------:|-------------|
| `source` | ✓ required | ✓ required | `"modrinth"` or `"github"` |
| `version` | ✓ required | ✓ required | Current installed version (for comparison) |
| `project_id` | optional | — | Modrinth project ID/slug (overrides hash lookup) |
| `repo` | — | ✓ required | GitHub `owner/repo` |
| `asset_pattern` | — | optional | Glob pattern for release asset (e.g. `paper-*.jar`) |
| `min_version` | optional | optional | Override global minimum |
| `max_version` | optional | optional | Override global maximum |

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/updateplugins` | `hanafubuki.updater.update` (OP) | Check and download plugin updates |
| `/updateplugins reload` | `hanafubuki.updater.update` (OP) | Reload configuration (planned) |
| `/updateplugins --dry-run` | `hanafubuki.updater.update` (OP) | Preview updates without downloading (planned) |

Default: **OP only**

## Releasing

Bump `version=` in [`gradle.properties`](gradle.properties) and push to `master`.  
GitHub Actions builds, signs, and publishes to Releases.

```bash
# Local build (for development)
./gradlew build
# Output: build/libs/hanafubuki-plugin-updater-<version>.jar
```

## Tech Stack

- **Java 21** + **Gradle Kotlin DSL**
- **Paper API** 1.21.9 (compileOnly)
- **Gson** for JSON parsing
- **SnakeYAML** for config (via Bukkit)
- **Modrinth API v2** (https://api.modrinth.com/v2)
- **GitHub REST API v3**

## Security

See [Security Documentation](docs/security.md) for details on:
- SHA-512 hash-based Modrinth identification (no search)
- Download verification (hash + plugin.yml name)
- Safe update mechanics (update folder + atomic move)
- Threat model and mitigations

## License

**GPLv3** — same as other Hanafubuki plugins.

© 2026 **ByteMe6** / **Hanafubuki-minecraft**

---

<div align="center">

*Part of the [Hanafubuki](https://github.com/Hanafubuki-minecraft) ecosystem*

</div>