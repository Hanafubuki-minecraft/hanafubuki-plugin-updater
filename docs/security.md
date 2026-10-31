# Security Documentation

## Overview

Hanafubuki Plugin Updater implements multiple security measures to prevent supply chain attacks and ensure only legitimate plugin updates are installed.

## Modrinth Security

### Problem: Search-by-Name Vulnerability

The previous implementation searched for plugins by name using `GET /v2/project?query={name}` and took the first result. This is vulnerable to:
- **Typosquatting**: Malicious plugins with similar names
- **Name collision**: Different plugins sharing the same name
- **Search manipulation**: Attackers influencing search rankings

### Solution: SHA-512 Hash-Based Identification

The plugin now identifies Modrinth projects exclusively by the SHA-512 hash of the installed JAR file:

1. **Compute SHA-512** of the currently installed plugin JAR
2. **Lookup via hash**: `GET /v2/version_file/{sha512}?algorithm=sha512` → returns `project_id`
3. **Fetch versions**: `GET /v2/project/{project_id}/version?loaders=["paper"]&game_versions=["1.21.9"]`
4. **Verify download**: After downloading, verify the SHA-512 matches `files[].hashes.sha512` from the API
5. **Verify plugin.yml name**: Extract `name` from `plugin.yml` inside the downloaded JAR and compare to the expected plugin name

### Fallback: Explicit Project ID

Users can optionally specify `project_id` in config.yml to bypass hash lookup:
```yaml
plugins:
  "WorldEdit":
    source: "modrinth"
    version: "7.3.12"
    project_id: "A1B2C3D4"  # or slug like "worldedit"
```

This is useful for:
- Plugins not yet on Modrinth when first installed
- Forks or custom builds

### Verification Flow

```mermaid
flowchart TD
    A[Installed JAR] --> B[Compute SHA-512]
    B --> C{project_id in config?}
    C -->|Yes| D[Use explicit project_id]
    C -->|No| E[GET /version_file/{sha512}]
    E --> F{Found?}
    F -->|Yes| G[Get project_id from response]
    F -->|No| H[ERROR: Not on Modrinth]
    D --> I[GET /project/{id}/version?loaders=paper&game_versions=1.21.9]
    G --> I
    I --> J[Find latest compatible version]
    J --> K[GET download URL for primary file]
    K --> L[Download to plugins/update/{name}.part]
    L --> M[Verify SHA-512 matches API]
    M --> N{Match?}
    N -->|No| O[DELETE & FAIL]
    N -->|Yes| P[Extract plugin.yml name]
    P --> Q{Name matches?}
    Q -->|No| O
    Q -->|Yes| R[ATOMIC MOVE to plugins/update/{name}.jar]
```

## GitHub Security

### Verification Measures

1. **Repository pinning**: Config requires explicit `owner/repo` format, no search
2. **Asset pattern matching**: Optional `asset_pattern` to select specific asset
3. **Plugin.yml name verification**: Extract `name` from downloaded JAR and verify matches expected
4. **HTTPS only**: All GitHub API and download URLs use HTTPS

### Limitations

- GitHub releases don't provide file hashes in API response
- Verification relies on plugin.yml name check only
- Consider using signed releases or checksums in release notes for additional security

## Update Process Security

### Safe Installation

- **Never overwrite** JARs in `plugins/` while server runs
- Download to **Bukkit update folder**: `plugins/update/` (configured via `bukkit.yml → settings.update-folder`)
- **Atomic move** using `Files.move(..., ATOMIC_MOVE)` 
- Paper swaps JAR on **next restart** only

### Network Security

- **Unique User-Agent** on all requests (required by Modrinth/GitHub)
- **HTTPS only** for all API and download endpoints
- **Request timeouts** (10s API, 60s downloads) prevent hanging
- **Async execution** - all network I/O off main thread

## Threat Model

| Threat | Mitigation |
|--------|------------|
| Malicious plugin with same name | SHA-512 hash identification |
| Compromised Modrinth CDN | SHA-512 verification against API |
| Compromised GitHub release | plugin.yml name verification |
| MITM on download | HTTPS + hash verification (Modrinth) |
| JAR swap during runtime | Update folder + atomic move + restart required |
| Config tampering | File permissions, op-only commands |

## Configuration Security

- `config.yml` should be readable only by server process
- GitHub token (if used) should have minimal scope (public_repo)
- No secrets logged in console output

## Reporting Security Issues

Report security vulnerabilities privately to the maintainers. Do not open public issues for security problems.