# Development

## Requirements

- Java 21 (the Gradle toolchain requests it)
- The Gradle wrapper in the repository (`./gradlew`)
- Network access to `repo.papermc.io` and Maven Central for dependencies

Builds and releases are done by GitHub Actions; a local build is only needed while developing.

## Project layout

```
build.gradle.kts            Gradle build (Kotlin DSL)
settings.gradle.kts         rootProject.name = "hanafubuki-plugin-updater"
gradle.properties           version=... (the only place the version is written)
src/main/resources/
  plugin.yml                commands, permissions; ${version} is filled in by processResources
  config.yml                default config with comments (copied as-is)
src/main/java/com/hanafubuki/updater/
  PluginUpdater.java        main class
  UpdateScheduler.java  JoinListener.java  ServerPlugins.java  Permissions.java
  command/  gui/  console/  config/  manager/  source/  http/  notify/  discovery/  health/  util/
src/test/java/com/hanafubuki/updater/
  testing/                  FakeHttpServer, TestHttp, TestJars
  contract/                 tests against the real APIs (tag "contract")
  config/ util/ http/ source/ manager/ ...   unit tests, one package per main package
docs/                       this documentation and config.example.yml
.github/workflows/          build.yml (CI), release.yml (releases)
```

Package responsibilities are described in [architecture.md](architecture.md#packages).

## Dependencies

All `compileOnly`, nothing is shaded into the jar:

| Dependency | Why |
|---|---|
| `io.papermc.paper:paper-api:1.21.9-R0.1-SNAPSHOT` (repo `https://repo.papermc.io/repository/maven-public/`) | Bukkit/Paper API, Adventure, MiniMessage |
| `com.google.code.gson:gson:2.11.0` | JSON (shipped by Paper) |
| `org.yaml:snakeyaml:2.2` | descriptor parsing (shipped by Paper) |

Tests use JUnit 5 (`junit-bom:5.11.4`) and the same libraries on the test classpath. There is no mocking
library: HTTP is tested against a real local server.

## Building

```bash
./gradlew build
# → build/libs/hanafubuki-plugin-updater-<version>.jar
```

`build` compiles with `-Xlint:unchecked -Xlint:rawtypes`, runs the unit tests and packages the jar.
`processResources` expands `${version}` in `plugin.yml` from `gradle.properties`; `config.yml` is copied
unchanged.

## Tests

### Unit and integration tests (`./gradlew test`, part of `build`)

The core packages (`util`, `http`, `source`, `manager`, `config` parsing, `discovery`, `notify`, `health`) do
not use the Bukkit API, so they are tested without a server:

- `testing/FakeHttpServer` is a real HTTP server on `localhost` (JDK `HttpServer`). Tests register routes
  (`json(path, body)`, `route(path, handler)`) and point a source at `server.uri(...)` through the
  constructors that take a base URL. Every request is recorded with method, path, query, headers and body, so
  tests can assert the User-Agent, that a token was or was not sent, conditional headers, redirects, retries.
- `testing/TestHttp` builds `HttpService` instances that do not really sleep between retries and records the
  requested delays.
- `testing/TestJars` writes small plugin jars with a `plugin.yml` or `paper-plugin.yml` (name, version,
  dependencies, api-version) for install, backup and dependency tests.
- `config/DefaultConfigTest` parses `src/main/resources/config.yml` and `docs/config.example.yml` and fails on
  any reported problem, and checks that the example uses every source and that every example entry passes its
  source's `validate()`. Keep both files valid when you change options.

### Contract tests (`./gradlew contractTest`)

`src/test/java/.../contract/` calls the real Modrinth, Hangar, Spiget and GitHub APIs (JUnit tag `contract`)
to catch API changes: e.g. LuckPerms on Modrinth is downloaded and verified by hash, ViaVersion on Hangar,
the Spigot exact-name rule, GitHub asset selection, signatures and advisories. They need internet access and
are **not** part of `build`; run them with the separate `contractTest` task. `GITHUB_TOKEN` in the environment
raises the GitHub limit; when GitHub refuses because of the rate limit, the GitHub tests are skipped instead of
failing.

## Continuous integration

| Workflow | Trigger | What it does |
|---|---|---|
| `.github/workflows/build.yml` | every push (all branches) and every pull request | Java 21 (Temurin), `./gradlew build --no-daemon`; uploads `build/reports/tests/` when it fails |
| `.github/workflows/release.yml` | push to `master` that changes `gradle.properties` or `src/main/resources/plugin.yml`; manual dispatch | reads `version` from `gradle.properties`, builds, creates tag `v<version>` if it does not exist, publishes a GitHub release with generated notes and `build/libs/*.jar` |

## Releasing

1. Change `version=` in `gradle.properties` (the only place).
2. Commit and push to `master`.
3. `release.yml` builds and publishes release `v<version>`.

The release jar is what servers download; this updater can update itself if you add an entry with
`source: github` and `repo: Hanafubuki-minecraft/hanafubuki-plugin-updater`.

## Adding a source

1. Implement `source/UpdateSource` in a new class (`id`, `validate`, `findLatest`). Use the injected
   `HttpService` for all requests (never a new client), take timeouts from `SourceSettings`, throw
   `IOException` with a message an admin can act on, and add a constructor that takes a base URL for tests.
2. Return `UpdateCandidate` with a checksum whenever the source publishes one; add a note when it does not.
   Never pick between several candidates by guessing.
3. Register the id in `SettingsParser.SOURCES` and the instance in `PluginUpdater.buildServices()`. If it needs a
   config section, parse it in `SettingsParser` (use `source("<id>")` for timeouts and retries) and add it to
   `UpdaterSettings` and `UpdaterSettings.downloadTimeout`.
4. Document the options in `src/main/resources/config.yml`, add an entry to `docs/config.example.yml`
   (`DefaultConfigTest` requires every source there) and describe it in [configuration.md](configuration.md),
   [api.md](api.md) and [security.md](security.md).
5. Write unit tests with `FakeHttpServer` and, if it is a public API, a contract test.

## Conventions

- Java 21, package `com.hanafubuki.updater`, group `com.hanafubuki`.
- No network or file I/O on the server main thread; Bukkit calls only on the main thread
  (`PluginUpdater.runSync`).
- Core packages stay free of `org.bukkit` imports.
- Messages use MiniMessage templates with the style tags `<primary> <accent> <success> <warning> <error>
  <muted>`; values go in through `Messages.text(...)` (plain text).
- Code comments and docs in English.
- When behaviour changes, update `docs/` in the same change.

## License

GPLv3, like the other Hanafubuki plugins. See [LICENSE](../LICENSE).
