<div align="center">

# Hanafubuki Plugin Updater

[![Paper](https://img.shields.io/badge/Paper-1.21.9-blue)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net/)
[![Gradle](https://img.shields.io/badge/Gradle-Kotlin%20DSL-green)](https://gradle.org/)
[![License](https://img.shields.io/badge/License-GPLv3-red)](LICENSE)
[![Modrinth](https://img.shields.io/badge/Source-Modrinth-purple)](https://modrinth.com/)
[![GitHub](https://img.shields.io/badge/Source-GitHub-black)](https://github.com/)

**Automatic plugin updates for Paper servers from Modrinth and GitHub**

[Русский](README.ru.md) | [Configuration](docs/configuration.md) | [Security](docs/security.md) | [Architecture](docs/architecture.md) | [Roadmap](docs/roadmap.md)

</div>

---

## How it Works

Hanafubuki Plugin Updater checks your configured plugins against Modrinth and GitHub for newer versions, downloads updates safely to the Bukkit update folder, and notifies operators to restart.

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Admin runs /updateplugins                                 │
├─────────────────────────────────────────────────────────────┤
│ 2. For each configured plugin:                               │
│    ├─ Modrinth: Compute SHA-512 of installed JAR             │
│    │         → GET /version_file/{hash} → project_id         │
│    │         → GET /project/{id}/version?loaders=paper       │
│    │         → Verify SHA-512 + plugin.yml name              │
│    └─ GitHub:  GET /repos/{owner}/{repo}/releases/latest     │
│               → Verify plugin.yml name                       │
├─────────────────────────────────────────────────────────────┤
│ 3. If newer version found:                                   │
│    └─ Download to plugins/update/{name}.jar.part             │
│       → Atomic move to plugins/update/{name}.jar             │
├─────────────────────────────────────────────────────────────┤
│ 4. Broadcast to ops: "Plugin X updated, try restarting"     │
├─────────────────────────────────────────────────────────────┤
│ 5. On next restart: Paper swaps JAR automatically            │
└─────────────────────────────────────────────────────────────┘
```

## Key Features

| Feature | Description |
|---------|-------------|
| **Secure Modrinth lookup** | SHA-512 hash of installed JAR → no search-by-name attacks |
| **GitHub releases support** | Owner/repo format, asset pattern matching |
| **Dual update modes** | Include-only or exclude-list (works in both modes) |
| **Version filtering** | Global and per-plugin min/max version constraints |
| **Safe installation** | Downloads to `plugins/update/`, atomic move, restart to apply |
| **Async operations** | All network I/O off main thread via `CompletableFuture` |
| **MiniMessage styling** | Configurable colors, gradients, formatting |
| **Operator notifications** | Broadcast on successful update |

## Setup

### Requirements
- **Java 21**
- **Paper 1.21.9** (also works on forks: Purpur, Pufferfish, etc.)
- Geyser/Floodgate/ViaVersion compatible

### Installation
1. Download the latest `.jar` from [Releases](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest)
2. Drop it into your server's `plugins/` folder
3. Start/restart the server
4. Edit `plugins/HanafubukiPluginUpdater/config.yml` (generated on first run)
5. Run `/updateplugins` to check for updates

### Configuration

Copy `config.yml` and customize:

```yaml
global:
  min_version: "1.0.0"
  max_version: "99.99.99"

modes:
  include_only: false          # true = only update plugins in include_list
  exclude_list: []             # plugins to skip (both modes)
  include_list: []             # plugins to update (include_only mode)

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

See [Configuration Reference](docs/configuration.md) for full schema.

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/updateplugins` | `hanafubuki.updater.update` | Check and download plugin updates |
| `/updateplugins reload` | `hanafubuki.updater.update` | Reload configuration (planned) |
| `/updateplugins --dry-run` | `hanafubuki.updater.update` | Preview updates without downloading (planned) |

Default permission: **OP only**

## Releasing

Built and published via GitHub Actions on version bump:

1. Update `version=` in `gradle.properties`
2. `git commit -am "Release vX.Y.Z"`
3. `git tag vX.Y.Z`
4. `git push origin master --tags`
5. GitHub Actions builds, signs, and publishes to Releases

### Local Build (for development)
```bash
./gradlew build
# Output: build/libs/hanafubuki-plugin-updater-<version>.jar
```

## Tech Stack

- **Java 21** + **Gradle Kotlin DSL**
- **Paper API** 1.21.9 (compileOnly)
- **Gson** for JSON parsing
- **SnakeYAML** for config (via Bukkit)
- **Modrinth API v2** (labrinth)
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