# Roadmap

## Current Version (1.0.12)

### Implemented Features
- ✅ Modrinth plugin updates with SHA-512 hash identification
- ✅ GitHub releases plugin updates
- ✅ Dual update modes: include-only and exclude-list
- ✅ Global and per-plugin version filtering (min/max)
- ✅ Async network I/O (CompletableFuture)
- ✅ Safe installation to Bukkit update folder
- ✅ SHA-512 verification for Modrinth downloads
- ✅ plugin.yml name verification for both sources
- ✅ MiniMessage console output styling
- ✅ Operator notifications on update
- ✅ Configuration via YAML with example file

### Known Limitations
- GitHub downloads lack hash verification (only plugin.yml name check)
- No hot-reload for config changes (requires restart or command re-run)
- Single-threaded update checks per source (sequential within source)
- No webhook/Discord notification integration
- No dry-run / preview mode

## Planned Features

### v1.1 - Reliability & Observability
- [ ] **Dry-run mode**: `/updateplugins --dry-run` to preview updates without downloading
- [ ] **Structured logging**: JSON log output option for log aggregation
- [ ] **Metrics**: Update check success/failure counts, duration histograms
- [ ] **Health check endpoint**: For monitoring systems
- [ ] **Config hot-reload**: `/updateplugins reload` to reload config without restart
- [ ] **Retry logic**: Exponential backoff for transient API failures

### v1.2 - Enhanced Sources
- [ ] **Hangar support**: PaperMC's official plugin repository
- [ ] **SpigotMC support**: Legacy but still used
- [ ] **Local file source**: Update from local filesystem/network share
- [ ] **Custom HTTP source**: Generic REST endpoint with JSON config

### v1.3 - GitHub Security Hardening
- [ ] **Release asset checksums**: Parse SHA-256/SHA-512 from release body or separate checksum file
- [ ] **Signed commits verification**: Verify GitHub release commit signatures
- [ ] **Dependabot-style alerts**: Notify when plugin has known vulnerabilities (via GitHub Advisory Database)

### v1.4 - Notifications & Integration
- [ ] **Discord webhook**: Send update notifications to Discord channel
- [ ] **Webhook generic**: POST JSON to configurable URL on update
- [ ] **In-game GUI**: `/updateplugins gui` for visual update management
- [ ] **Scheduled checks**: Configurable automatic update checks (daily/weekly)

### v1.5 - Advanced Features
- [ ] **Rollback support**: Keep previous JAR version, command to revert
- [ ] **Dependency awareness**: Check if updated plugin breaks dependencies
- [ ] **Bulk operations**: Update all / update selected via command
- [ ] **Plugin profiling**: Track which plugins update frequently vs rarely

## Architecture Improvements

### Refactoring
- [ ] **Source interface**: Extract `SourceHandler` interface for easier extension
- [ ] **Plugin discovery**: Auto-detect installed plugins and suggest config entries
- [ ] **State persistence**: Track last check time, last version seen per plugin
- [ ] **Cancellation**: Support cancelling long-running update checks

### Testing
- [ ] **Unit tests**: VersionUtils, ConfigManager parsing
- [ ] **Integration tests**: Mock Modrinth/GitHub API responses
- [ ] **Contract tests**: Verify API compatibility

## Non-Functional Requirements

### Performance
- Target: Check 50 plugins in < 30 seconds
- Parallel API requests across sources
- Connection pooling for HTTP client

### Compatibility
- Paper 1.21.x (current: 1.21.9)
- Geyser/Floodgate (Bedrock compatibility)
- ViaVersion (version protocol support)
- Java 21 LTS

### Operational
- Zero-downtime updates (restart required only for swap)
- Idempotent operations (safe to run multiple times)
- Clear error messages for operators

## Changelog Format

All changes follow [Keep a Changelog](https://keepachangelog.com/) format:
- `Added` for new features
- `Changed` for changes in existing functionality
- `Deprecated` for soon-to-be removed features
- `Removed` for now removed features
- `Fixed` for bug fixes
- `Security` for vulnerability fixes

## Release Process

1. Update version in `gradle.properties`
2. `git commit -am "Release vX.Y.Z"`
3. `git tag vX.Y.Z`
4. `git push origin master --tags`
5. GitHub Actions builds and publishes release

## Contributing

See `DEVELOPMENT.md` for build and contribution guidelines.