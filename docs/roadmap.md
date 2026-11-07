# Roadmap

## Status in 1.6.0

Everything planned for v1.1 to v1.5 is implemented since 1.5.0.

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
- [x] Automatic detection with op confirmation (`plugins.json`), config.yml never written by the plugin (1.6.0)

## Changelog

### 1.6.0

**Added**

- Automatic detection (`global.auto_detect`, default `true`): at startup, before every scheduled check and
  after `/updateplugins reload`, installed plugins that are neither in config.yml nor in `modes.exclude_list`
  are identified (Modrinth hash of the jar, then the plugin.yml `website`) and stored in the new
  `plugins.json` as `pending` or `unidentified`. Nothing found is updated until an op confirms it.
- `plugins.json`: found plugins with status, source, options, how they were found, version, dates and who
  decided. Written only by the updater, atomically; a broken file is kept as `plugins.json.broken`.
- `/updateplugins pending`, `confirm <plugin|all>`, `reject <plugin>` and the permission
  `hanafubuki.updater.confirm` (default op, part of `hanafubuki.updater.*`). Clickable `[confirm]`,
  `[reject]` and `[hide]` in chat.
- GUI: **Found plugins** button (slot 46, a bell when plugins wait) and view (orange waiting, lime confirmed,
  red rejected, gray no source; left click confirm, right click reject, **Confirm all waiting**, **Search
  now**). Plugin items show whether they come from config.yml or plugins.json.
- Players with `hanafubuki.updater.notify` get "New plugin(s) found … [review]" when detection finds plugins,
  and on join the number of plugins waiting for confirmation.
- `/health`: `plugins_pending`. JSON log event `plugins_detected`.

**Changed**

- config.yml belongs to the user: the plugin creates it on first start and never writes to it again. Its
  `plugins:` entries always win over plugins.json (case-insensitive name). Versions are kept only in
  `state.json`; `version` in plugin entries is informational and never written.
- Tracked plugins (runs, `status`, GUI, tab completion, `/health` `plugins_tracked`, metrics) = config.yml
  entries + confirmed plugins.json entries. `modes.include_only` / `exclude_list` apply to confirmed plugins
  too; `confirm` warns when a plugin is still filtered out.
- `/updateplugins discover` searches now and looks up plugins without a source again; results go to
  plugins.json. Unidentified plugins are also retried when their version changes or the last lookup failed
  for a network reason.
- GUI clicks are handled one tick after the click event (Bukkit does not allow opening or closing inventories
  inside it); this also prevents double actions.

**Removed**

- `global.auto_update_config`, `global.auto_discover`, `global.auto_discover_on_startup`. If present they are
  reported as config problems ("removed in 1.6.0, delete this line"); the plugin keeps working.
- `/updateplugins discover add` (prints a hint to use `pending` / `confirm`).

**Performance**

- The HTTP ETag cache is limited to about 8 MB of response text as well as 512 entries; bodies above a quarter
  of that are not cached.
- SHA-512 / SHA-256 of installed jars are cached by path, size and modification time, so checks no longer
  re-hash large jars every time.
- The background and notification threads exit after 30 s without work and start again on demand.
- The startup reconciliation of staged updates does its file checks off the main thread; detection lookups
  run on the background thread.

**Breaking / migration**

- Delete the three removed `global` keys from config.yml.
- Entries that `discover add` or `auto_discover_on_startup` added to config.yml in earlier versions stay as
  normal manual entries; nothing has to be moved.
- Plugins that were not in config.yml are found again and wait for confirmation; run
  `/updateplugins pending` after the update.

### 1.5.3

**Fixed**

- GitHub: `original-*.jar` files that `maven-shade-plugin` leaves next to the real jar are ignored, so Maven
  releases with both jars no longer need an `asset_pattern`.

### 1.5.2

**Fixed**

- When Modrinth has separate builds of the same version per loader (BlueMap `5.16-paper` and `5.16-spigot`),
  the build for the first loader in `modrinth.loaders` is used, so Paper servers get the Paper build.
- After a restart, players with `hanafubuki.updater.notify` were told that the just-applied updates still
  wait for a restart; staged records are now checked against the update folder when the plugin starts.

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
