# Sources and HTTP

How each source finds, chooses and verifies a new version, and how the plugin talks HTTP. Configuration keys
are listed in [configuration.md](configuration.md#plugins); the formats the plugin itself produces (webhook
JSON, console JSON, `/health`, `/metrics`) are documented there as well.

Every source implements `source/UpdateSource`:

```java
String id();                                   // "modrinth", "github", ...
List<String> validate(PluginEntry entry);       // config problems, empty if usable
Optional<UpdateCandidate> findLatest(UpdateRequest request) throws IOException;
```

`UpdateCandidate` carries the version, download URL, expected checksum (or `null`), headers for the download,
a `Freshness` hint (`NEWER`, `SAME`, `UNKNOWN`), a page URL and notes for admins. Whatever the source, the
`Installer` then rejects a download whose checksum does not match or whose `plugin.yml` / `paper-plugin.yml`
`name` differs from the installed plugin ([architecture.md](architecture.md#staging-installer)).

| Source | API | Identifies the project by | Checksum | "Newer" decided by |
|---|---|---|---|---|
| `modrinth` | `https://api.modrinth.com/v2` | SHA-512 of the installed jar, or `project_id` | SHA-512 (required) | publish dates when the installed file is known, else version strings |
| `github` | `https://api.github.com` | `repo` | asset digest, sidecar, checksum list or release notes, if published | version strings (tag name) |
| `hangar` | `https://hangar.papermc.io/api/v1` | `project_slug` | SHA-256 | identical file, else version strings |
| `spigotmc` | `https://api.spiget.org/v2` | `resource_id`, or a unique exact `resource_name` | none | version strings |
| `local` | file system | descriptor `name` in each jar | sidecar file or `checksum` | identical file, else version strings |
| `custom` | your endpoint | `url` | `checksum_path` / common fields / `checksum` | version strings |

## Modrinth

Docs: <https://docs.modrinth.com>. No token is used.

1. **Identify the installed jar.** `GET /v2/version_file/{sha512}?algorithm=sha512` with the SHA-512 of the
   running jar. `404` means the file is not on Modrinth. There is never a search by name.
2. **Project.** `project_id` from the entry (id or slug, 2–64 characters of `A-Z a-z 0-9 _ . -`) or, if not
   set, the project of the identified file. If neither exists the check fails with
   "The installed jar is not a file published on Modrinth. Set project_id …".
3. **Versions.** `GET /v2/project/{id}/version?loaders=["paper"]&game_versions=["1.21.9"]` with the
   entry's or global `loaders` and `game_versions` (empty `game_versions` = the server's version).
4. **Filter.** Keep versions whose `version_type` is in `version_types` (default `release`) and that have a
   usable jar: the file marked `primary`, or the only `.jar` file. Versions with several non-primary jars are
   skipped.
5. **Choose** the version with the newest `date_published`.
6. **Checksum.** `files[].hashes.sha512` of that jar. A version without SHA-512 is an error.
7. **Newer?** If the candidate's hash equals the installed jar's hash → same. If the installed file was
   identified and belongs to the same project → newer only if published later. This makes version strings
   like `v5.5.71-bukkit` irrelevant. Otherwise the version numbers are compared.

Page URL: `https://modrinth.com/project/{project_id}/version/{version_id}`.

## GitHub releases

Docs: <https://docs.github.com/en/rest/releases>. Headers: `Accept: application/vnd.github+json`,
`X-GitHub-Api-Version: 2022-11-28`, and `Authorization: Bearer <token>` when `github.token` is set.

1. **Release.**
   - default: `GET /repos/{owner}/{repo}/releases/latest` (GitHub's latest: no drafts, no pre-releases);
   - `include_prereleases: true`: `GET /repos/{owner}/{repo}/releases?per_page=20`, the first non-draft
     release.
   - `404` → no version. `403`/`429` with `X-RateLimit-Remaining: 0` → error that suggests setting a token.
2. **Asset.** With `asset_pattern`: the assets whose name matches the glob. Without: all `.jar` assets except
   `*-sources.jar` and `*-javadoc.jar`. Exactly one must remain. None or several is an error naming the
   assets ("Set asset_pattern to choose one"); the plugin never guesses.
3. **Checksum**, first match wins:
   1. the asset's `digest` field computed by GitHub (`sha256:…`);
   2. a sidecar asset `<asset>.sha512`, `<asset>.sha256` or `<asset>.sha1` (at most 64 KB);
   3. a checksum list asset (`checksums.txt`, `SHA256SUMS`, `SHA256SUMS.txt`, `SHA512SUMS`,
      `SHA512SUMS.txt`, `checksums.sha256`, `checksums.sha512`, case-insensitive) with `<hash>  <file>` lines;
   4. the release notes: a SHA-256/SHA-512 hex digest on the line that names the asset or the line after it;
      otherwise a single digest on lines labelled `sha256`, `sha-256`, `sha512`, `sha-512` or `checksum`.

   Without any of these the update is still installed, with the note
   "GitHub release … publishes no checksum for …".
4. **Signatures** (`signatures: warn|require`). `GET /repos/{repo}/git/ref/tags/{tag}`. For an annotated tag,
   `GET /repos/{repo}/git/tags/{sha}`: verified if `verification.verified` is true; otherwise the commit it
   points to is checked. For a lightweight tag the commit is checked directly:
   `GET /repos/{repo}/commits/{sha}` → `commit.verification.verified`. `warn` adds a note, `require` fails the
   check.
5. **Download.** With a token: the API asset URL (`assets[].url`) with `Accept: application/octet-stream`
   and the token; this also works for private repositories. GitHub answers with a redirect to its file host,
   and `HttpService` drops the `Authorization` header there. Without a token: `browser_download_url`.

The version is the tag name; `v`-prefixed tags are compared correctly.

### Security advisories

`GitHubAdvisories` runs for every plugin entry with a valid `repo` (any source) while `github.advisories` is
on: `GET /repos/{owner}/{repo}/security-advisories?state=published&per_page=100` with the GitHub headers
above. Withdrawn advisories are ignored. An advisory applies when the **installed** version matches one of its
`vulnerable_version_range` expressions (`< 1.2.3`, `>= 1.0, < 1.4.2`, `= 2.0.0`). `404` means no advisories.
Errors only add a note to the plugin's result. Alerts are reported in the console, in game, on join, to
Discord (`security`) and to the webhook.

## Hangar

Docs: <https://hangar.papermc.io/api-docs>.

1. `GET /api/v1/projects/{slug}/versions?limit=25&offset=0&platform=PAPER&channel={channel}` (no `channel`
   parameter when the channel is `*`). `Owner/Slug` in the config is reduced to the slug. `404` → error
   "Hangar project … does not exist".
2. Keep versions that are in the channel (case-insensitive), list the server's Minecraft version under
   `platformDependencies.PAPER`, and have a PAPER download. Accepted declarations: the exact version
   (`1.21.9`), a range (`1.21-1.21.10`) or a wildcard (`1.21.x`).
3. Download: `downloads.PAPER.downloadUrl`, or `externalUrl` only if it is an `https` link to a `.jar` file.
4. Choose the newest `createdAt` among the 25 newest versions returned.
5. Checksum: `downloads.PAPER.fileInfo.sha256Hash`. If missing, a note is added.
6. If that hash equals the installed jar → same; otherwise the version names are compared.

## SpigotMC (Spiget)

Docs: <https://spiget.org/documentation>. SpigotMC has no official download API; Spiget mirrors it.

1. **Resource id.** `resource_id`, or a name search:
   `GET /v2/search/resources/{name}?field=name&size=100&page=N&fields=id,name`, all pages (at most 20). Only
   results whose name equals `resource_name` (case-insensitive) count; exactly one must exist. The first
   search result is never taken blindly.
2. `GET /v2/resources/{id}`. Premium resources are refused.
3. `file.type` `.jar` → `GET /v2/resources/{id}/download`. `external` → used only if `file.externalUrl` is an
   `https` link to a `.jar` file; links to web pages are refused with a hint to use the github or modrinth
   source.
4. Version: `name` of `GET /v2/resources/{id}/versions/latest`.
5. No checksum is available. Every result carries the note
   "SpigotMC publishes no checksums; only the plugin name is verified".

## Local folder

No network. `path` is a directory (its `*.jar` files) or one jar. Jars whose descriptor `name` differs from
the plugin are ignored. The version comes from the descriptor or, with `pattern`, from group 1 of the regex
applied to the file name. The newest version wins (`VersionUtils`). Checksum: a sidecar
`<jar>.sha512` / `.sha256` / `.sha1` (a sidecar with no valid digest is an error), or `checksum` for a
single-file `path`. If the newest jar is byte-identical to the installed one, it is "same".

## Custom JSON endpoint

1. `GET {url}` with `Accept: application/json`, the configured `headers` and the `auth` header. Only `https`
   unless `allow_http: true`.
2. Read version, download URL and checksum with the configured JSON paths or the common field names
   ([configuration.md](configuration.md#custom)). No version → no version found; no download URL → error.
3. The download URL may be relative to `url` and must also be `https` (unless `allow_http`).
4. The headers are sent with the download only if it is on the same origin (scheme, host, port) as `url`.

## HTTP layer

All traffic goes through `http/HttpService`.

- **One shared `java.net.http.HttpClient`** (connect timeout 10 s) for the whole plugin, so connections are
  pooled and reused. Sources get views of it with their own retry policy (`withRetry`).
- **User-Agent** on every request: `ByteMe6/hanafubuki-plugin-updater/<plugin version>`. Modrinth requires a
  unique one.
- **Timeouts** per request: the source's `timeout` for API calls, `download_timeout` for downloads; 15 s for
  notifications.
- **Retries** on network errors, HTTP `429` and `5xx`: up to `max_retries`, delay
  `min(base · 2^attempt + jitter, max)` with jitter up to `min(base, 1 s)`. A `Retry-After` header given in
  seconds is honoured, capped at 60 s. Each retry is logged.
- **Redirects** are followed by hand (`301, 302, 303, 307, 308`, at most 5). When a redirect leaves the
  origin (scheme, host, port), every header except `User-Agent` and `Accept` is dropped, so tokens never reach
  CDNs or other hosts. A redirect from `https` to anything else is refused. Notification POSTs do not follow
  redirects.
- **Conditional requests.** GET responses with `ETag` or `Last-Modified` are kept in an in-memory LRU cache
  of 512 entries (`HttpCache.inMemory(512)`). The next request sends `If-None-Match` / `If-Modified-Since`;
  a `304` is answered from the cache. GitHub does not count `304` answers against the rate limit. The cache is
  lost on restart.
- **Size limits.** API bodies are read up to 32 MB; downloads up to `global.max_download_mb` (checked against
  `Content-Length` and while streaming); notification answers up to 64 KB.
- **Interruptible.** An interrupted thread (cancelled run) aborts the request, a retry wait or a download with
  `InterruptedIOException`.
- **Logging.** URLs are logged as scheme, host and path only; query strings, which may contain secrets, are
  never logged.

### Rate limits

| Service | Limit | What the plugin does |
|---|---|---|
| Modrinth | 300 requests per minute per IP | 2 requests per plugin (hash lookup, version list) plus the download |
| GitHub | 60 per hour without token, 5000 with | 1 request per plugin (+1 for advisories, +2–3 with signatures, +1 per sidecar/checksum list). `304` answers are free. Set `github.token` for more than a handful of plugins. |
| Hangar, Spiget | not documented as strict | 1–3 requests per plugin; a name search on Spiget can take up to 20 pages |

`429` and `5xx` answers are retried as described above.
