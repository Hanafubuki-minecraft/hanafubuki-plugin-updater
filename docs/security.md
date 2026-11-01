# Security

An updater downloads code that the server runs with full privileges on the next restart. This page lists what
can go wrong, what the plugin does about each case, and what it cannot protect against.

## Threat model

| Threat | Example | Mitigation |
|---|---|---|
| Wrong plugin substituted | A look-alike project with the same name; the first search result is someone else's plugin | No name searches on Modrinth (the installed jar is identified by its SHA-512); `project_slug`, `repo`, `resource_id` are explicit; a Spigot `resource_name` must match exactly one resource; GitHub assets are never guessed; every downloaded jar must contain a descriptor whose `name` equals the installed plugin's name |
| Tampered download / MITM | A proxy or mirror changes the file in transit | HTTPS everywhere; redirects from `https` to `http` are refused; `http` for custom sources only with `allow_http: true`; checksums from the source are verified before staging and a mismatch rejects the file |
| Malicious or compromised source | A hijacked author account publishes a backdoored release on the real project | Cannot be detected by a hash (the hash comes from the same source). Reduced by: `github.signatures: require`, `schedule.check_only: true` plus manual review, `max_version` pins, the dependency check, GitHub security advisories, backups and one-command rollback |
| Credential leak | A GitHub token or API key forwarded to a CDN or a third-party host | Tokens are only attached to requests to the API origin; on a redirect to another origin every header except `User-Agent` and `Accept` is dropped; custom source `headers`/`auth` go to the download only when it is on the same origin as `url`; secrets can live in environment variables (`${NAME}`); URLs are logged without query strings |
| Oversized files / zip bombs | A multi-GB "jar", a huge API answer or a crafted descriptor | Download limit `global.max_download_mb` (checked on `Content-Length` and while streaming); API bodies ≤ 32 MB; descriptor files read ≤ 1 MB and parsed with SnakeYAML's `SafeConstructor`; sidecar and checksum-list assets ≤ 64 KB |
| Config mistakes | A typo in a key, a wrong source, an invalid regex or URL | `SettingsParser` reports every problem with its path and falls back to safe defaults; invalid YAML keeps the previous settings; each source's `validate()` turns a bad entry into `FAILED: invalid config` without affecting others; the default config updates nothing |
| Half-written files | Crash or power loss during a download or a state write | Downloads go to a temporary `.part` file that is always deleted; staged jars, backups and `state.json` are moved into place atomically; running jars in `plugins/` are never written |
| Broken update | A new version needs a missing plugin or a newer server | `dependencies.check: block` refuses it; backups and `/updateplugins rollback`; a rolled-back plugin is held so it is not reinstalled automatically |
| Unauthorised use in game | A player triggers updates or rollbacks | Every sub-command and GUI action has its own permission node, all default to op; plugin names and versions in messages are inserted as plain text (no MiniMessage injection) |
| Monitoring endpoint exposure | `/health` reachable from the internet | Off by default; binds to `127.0.0.1` by default; answers only `GET`; exposes counts and versions, no config or secrets |
| Notification abuse | A plugin name containing `@everyone` | Discord `allowed_mentions` is limited to roles and users; webhook URLs must be `https` |

## Verification per source

| Source | Checksum | Other checks |
|---|---|---|
| Modrinth | SHA-512 from the API, required | project identified by hash or explicit `project_id` |
| GitHub | GitHub's asset digest, a sidecar or checksum list asset, or a hash in the release notes, when published | optional tag/commit signature verified by GitHub (`signatures`) |
| Hangar | SHA-256 from the API, when published | external downloads only as `https` `.jar` links |
| SpigotMC | none | premium resources refused; external links only as `https` `.jar` links |
| Local | sidecar `.sha512`/`.sha256`/`.sha1` or `checksum`, when present | — |
| Custom | `checksum_path`, common fields or `checksum`, when present | `https` only by default |

For every source: the jar must contain `paper-plugin.yml` or `plugin.yml` with the installed plugin's `name`,
and, with `dependencies.check`, its hard dependencies must be loaded and its `api-version` must not be newer
than the server.

## What is not protected

- **SpigotMC downloads have no checksum.** Spiget publishes none, so integrity rests on TLS and the plugin
  name check. Prefer Modrinth, Hangar or GitHub for the same plugin when available.
- **GitHub releases without a published checksum** rely on TLS and the name check as well. The plugin adds a
  note to such results.
- **A compromised upstream** (author account, CI, signing key) can publish a release with a valid checksum
  and signature. The plugin installs what the project publishes. Use `check_only` and review if that risk
  matters to you.
- **`signatures` uses GitHub's "verified" flag.** It proves the tag or commit was signed by a key GitHub
  associates with the author, not by a key you pinned.
- **Advisories** come only from the repository's own published GitHub security advisories, and only for
  entries with `repo`.
- **The updater runs with the server's permissions.** Anything it stages runs with full access after the
  next restart. Anyone who can edit `config.yml` (or `plugins/update/`) can make the server run arbitrary
  code; protect the data folder like the server itself.
- **The health endpoint has no authentication.** Keep it on `127.0.0.1` or behind a firewall.
- **Secrets in config.yml are plain text** unless you use `${NAME}` environment variables.

## Recommendations

- Use `${GITHUB_TOKEN}` and a token with the smallest scope that can read the repositories you need.
- Keep `dependencies.check: block` and `backups.enabled: true`.
- Use `github.signatures: warn` (or `require` for projects that sign their tags/commits).
- For critical plugins, use `schedule.check_only: true` and install with `/updateplugins update <plugin>`
  after reading the release notes.
- Grant `hanafubuki.updater.check` / `status` to moderators, keep `update`, `rollback`, `reload` for admins.

## Reporting a vulnerability

Report security problems privately to the maintainers of
[Hanafubuki-minecraft/hanafubuki-plugin-updater](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater)
(GitHub → Security → Report a vulnerability, if enabled), not in a public issue.
