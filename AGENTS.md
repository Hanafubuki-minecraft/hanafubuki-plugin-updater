# Agent instructions

## Project
Hanafubuki Plugin Updater: Paper plugin that updates other plugins on the Hanafubuki Minecraft server.
Plugin source is Modrinth (modrinth.com). Spelling: M-O-D-R-I-N-T-H.
Modrinth API: https://api.modrinth.com/v2, docs: https://docs.modrinth.com
GitHub releases are the secondary source.
Never invent URLs, repo names or API endpoints. If unsure, fetch the docs.

## Working rules
- Never announce an action without doing it. If you say you will create a file, call the tool in the same message.
- Use real tool calls only. Never write tool calls as plain text.
- If something is unclear, ask the user instead of guessing.
- Do only what the current task asks. Do not create extra files (tests, examples) unless asked.
- After finishing a task, list created/changed files and stop.
- Docs and code comments in English.

## Paths
Project root: /home/samedy/hanafubuki-plugin-updater
Always use absolute paths starting with "/". Never create nested "home/samedy/..." dirs.
All docs are in docs/ folder, check them if something unclear.

## Tech stack
- Java 21, Gradle (Kotlin DSL: build.gradle.kts, settings.gradle.kts)
- Runs as: Paper plugin (plugin.yml, extends JavaPlugin)
- Target server: Paper 1.21.9 (also Geyser/Floodgate, ViaVersion)
- Dependency: compileOnly("io.papermc.paper:paper-api:1.21.9-R0.1-SNAPSHOT"),
  repo https://repo.papermc.io/repository/maven-public/
- group = "com.hanafubuki", base package: com.hanafubuki.updater
- Version lives only in gradle.properties (version=...), expanded into plugin.yml
- Builds and releases are done by GitHub Actions on version bump; do not build locally.
- Repo goes to GitHub org Hanafubuki-minecraft.
- Server runs in Crafty Controller (Docker) on nixserver.

## Modrinth API notes
- Every request must send a unique User-Agent header (Modrinth requires it),
  e.g. "ByteMe6/hanafubuki-plugin-updater/<version>".
- Versions: GET /v2/project/{id|slug}/version with loaders=["paper"]
  and game_versions=["1.21.9"] filters.

## Plugin update mechanics
- Never overwrite jars in plugins/ while the server runs.
- Download new jars into the Bukkit update folder: plugins/update/
  (bukkit.yml -> settings.update-folder, default "update").
  The file name must match the existing jar's name; Paper swaps it on next restart.
- All network I/O (Modrinth, GitHub) runs async, never on the main thread.
- After queuing updates, notify ops: "Plugin ___ updated, try restarting".

## Features
- Automatic plugin updates from Modrinth and GitHub
- Version checking and compatibility
- Easy configuration via YAML files
- Console-friendly output

## Configuration
- Config file example: config.example.yml
- Sources: Modrinth API (https://api.modrinth.com/v2), GitHub releases
- Plugin list with version compatibility
- Version filtering: global min/max
- Two update modes: included only, or everything except excluded
- Exclusion lists work in both modes
- Message style: MiniMessage, styled via config.yml -> style (same as other Hanafubuki plugins), configurable colors

## Architecture
- Main class: com.hanafubuki.updater.PluginUpdater
- config/  - config manager
- manager/ - repository managers
- source/  - source handlers (Modrinth, GitHub)
- util/    - utilities
- console/ - console output

## License
GPLv3, same as other Hanafubuki plugins.

## Development approach
1. Start with documentation and architecture
2. Create config file examples
3. Implement core classes step by step
4. Follow existing Hanafubuki code patterns and styling

## Subagents
Subagents are started with the `subagent` tool. It is not a skill.
Give the subagent full context: it does not see this conversation.

## Definition of done
- After every code change run `./gradlew build`. A task is done only when it passes. Show the output.
- Never write placeholder code or comments like "In a real implementation", "For now", "simulate". Implement it or ask.
- Never say something is done without the command output proving it.
- Read docs/ before any design decision. If behavior changes, update docs/ in the same task.
- After a finished task: `git add -A && git commit -m "<what>" && git push origin master`.
- One task per session. Do not touch files outside the task.

## Important notes
Check docs for everything that could be unclear in your implementing, if something still unclear ask user, do not guess, never. 
Use github, commit for every feture, after some part of job done change version to newer and commit it to github, do not forget about pushes. You are workign on real, woreking plugin, not a prototype.
After big part of job done, give short overview to user, on russian