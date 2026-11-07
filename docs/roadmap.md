# Roadmap

## Status in 1.5.0

Everything planned for v1.1 to v1.5 is implemented in 1.5.0.

### v1.1 – Reliability and observability

- [x] Dry run: `/updateplugins check`, `/updateplugins --dry-run`, `update --dry-run`, `schedule.check_only`
- [x] JSON log lines (`console.json_format`)
- [x] Metrics: `/updateplugins metrics`, Prometheus output, `/metrics` endpoint
- [x] Health endpoint (`health_check`, `GET /health`)
- [x] Config reload: `/updateplugins reload`
- [x] Retries with exponential backoff, jitter and `Retry-After`

### v1.2 – More sources

- [x] Hangar
- [x] SpigotMC (via Spiget)
- [x] Local folder / network share
- [x] Custom JSON endpoint

### v1.3 – GitHub security

- [x] Release asset checksums: GitHub's asset digest, sidecar files, checksum lists, release notes
- [x] Signature verification of the release tag or its commit (`signatures: off | warn | require`)
- [x] Security advisories for every entry with `repo` (`github.advisories`)

### v1.4 – Notifications and integration

- [x] Discord webhook (events, username, avatar, role mention)
- [x] Generic JSON webhook with custom headers
- [x] In-game GUI: `/updateplugins gui`
- [x] Scheduled checks: interval, daily, weekly, on startup

### v1.5 – Advanced features

- [x] Rollback: backups before every update, `/updateplugins backups`, `rollback`, `unstage`, GUI backups view
- [x] Dependency awareness: missing hard dependencies and too new `api-version` (`dependencies.check`)
- [x] Bulk operations: update all, or `update <plugin> <plugin> …`, from command and GUI
- [x] Profiling / statistics: checks, updates, failures, average update interval (`/updateplugins stats`)
- [x] State persistence: `state.json`
- [x] Cancellation: `/updateplugins stop` interrupts network calls
- [x] Connection pooling: one shared HTTP client
- [x] ETag / Last-Modified caching
- [x] Unit and integration tests against a local HTTP server; contract tests against the real APIs

### Architecture items

- [x] Source interface (`UpdateSource`) for all six sources
- [x] Plugin discovery with evidence (Modrinth hash, website) instead of guessing

## Changelog

### 1.5.1

**Fixed**

- Plugins that Paper remaps (Spigot-mapped plugins such as GSit) were checked through the remapped copy in
  `plugins/.paper-remapped/`, so Modrinth could not identify them; the original jar is used now.
- GitHub: a repository without releases, a private repository without a token and a rejected token
  (HTTP 401) each get a clear message instead of retries that used up the API rate limit.
- Modrinth projects that publish only beta versions (Geyser) report which `version_types` to set.
- Jars downloaded from other sites get a hint to set `project_id`.

### 1.5.0

The plugin was rewritten. Configs from 1.2.x keep working: the same sections and plugin entries are read,
`console.colors` is still honoured, and invalid values are reported instead of breaking the plugin.

**Added**

- Schedule (`schedule`), backups and rollback (`backups`, `rollback`, `unstage`, `backups` commands), dependency
  check (`dependencies.check`), `state.json` with per-plugin statistics (`stats`), `status`, `stop`.
- GitHub: asset checksums, signature verification, pre-releases, private repositories via token, security
  advisories for any source.
- Notifications: Discord, generic webhook, on-join reminders, `/updateplugins notify test`.
- Inventory GUI (`/updateplugins gui`).
- One permission node per sub-command (`hanafubuki.updater.*` tree), aliases `/pluginupdater` and `/hpu`.
- `global.parallelism`, `global.max_download_mb`, `health_check.bind_address`.
- `style` section with `primary`, `accent`, `success`, `warning`, `error`, `muted` and `prefix`.
- `/metrics` Prometheus endpoint.

**Changed**

- Modrinth: newer-or-not is decided by Modrinth publish dates when the installed file is known; versions
  filtered by `version_types`.
- `version` in plugin entries is informational; the installed version is read from the server.
- Hangar: channel filter (`channel`, `"*"`), platform version ranges and `.x` wildcards.
- Discovery adds only plugins with evidence; others are listed.
- Default `download_timeout` raised from 60 to 120 seconds.
- Gson, SnakeYAML and MiniMessage are used from Paper at runtime; only the Paper API is needed to compile.

**Fixed** (bugs of 1.2.2)

- A dry run racing with a normal run could install updates; dry run is now an option of each run and only one
  run can be active.
- Redirects were never followed, so GitHub and SpigotMC downloads failed.
- `json-path` was missing from the jar, so the custom source could not work; it now uses a built-in JSON path
  subset.
- `v`-prefixed tags such as `v2.1.0` were read as `0.0.0`.
- The Spigot name search took the first search result; now only a unique exact match is accepted.
- Auto-discovery added wrong sources.
- Hangar checksums were never verified.
- Credentials were sent to download hosts on other domains.
- The health endpoint listened on all interfaces; it now binds to `127.0.0.1` by default.
- Update notifications were broadcast to all players; now only to `hanafubuki.updater.notify`.
- Config writes from parallel checks were not thread-safe.
- The default `config.yml` was not valid YAML.
- Unquoted `off` and `4:30` in YAML (read as `false` and `270`) are understood.
- Server locale no longer changes JSON output or messages (`Locale.ROOT` everywhere; Turkish locale broke
  webhook JSON).
- Plugins that Paper remaps (Spigot-mapped plugins such as GSit) were checked through the remapped copy
  in `plugins/.paper-remapped/`, so Modrinth could not identify them; the original jar is used now.
- GitHub repositories without releases or private repositories without a token now give a clear message
  instead of retrying the default branch and spending the API rate limit.
- Found while testing the rewrite: a cancelled run with queued plugins hung forever; a new run was refused
  right after the previous one finished; Discord messages over 6000 characters were rejected; reloading
  could block the main thread for up to 5 seconds.

**Security**

- Cross-origin redirects drop all headers except `User-Agent` and `Accept`; `https` → `http` redirects are
  refused; custom sources require `https` unless `allow_http: true`.
- Size limits for downloads, API answers, descriptors and checksum files.

## Future ideas

Not implemented; listed so they are not forgotten.

- Velocity / proxy support (updating proxy plugins, coordinated restarts).
- Per-plugin schedules and per-plugin `check_only`.
- Keeping the HTTP cache across restarts.
- Installing missing dependencies of a new version automatically instead of blocking it.
- Signature checks against pinned keys (GPG / Sigstore) instead of GitHub's "verified" flag.
- Advisories from sources other than GitHub repository advisories.
- A restart helper that tells players before a restart that applies staged updates.
