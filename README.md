# Hanafubuki Plugin Updater

[![Release](https://img.shields.io/github/v/release/Hanafubuki-minecraft/hanafubuki-plugin-updater)](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

A Bukkit/Spigot/Paper plugin that automatically updates Minecraft plugins from Modrinth and GitHub sources.

## Features

- **Automatic plugin updates** from Modrinth and GitHub sources
- **Version checking and compatibility** with Paper 1.21.9
- **Easy configuration** via YAML files
- **Console-friendly output** with MiniMessage styling
- **Two update modes**: included only, or everything except excluded
- **Exclusion lists** work in both modes
- **Safe updates** - never overwrites jars while server runs

## Requirements

- Minecraft Server: Paper 1.21.9
- Java 21
- Bukkit/Spigot/Paper API

## Installation

1. Download the latest `.jar` file from the [releases](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases)
2. Place the jar file in your `plugins` folder
3. Start/restart your server
4. The plugin will generate a default configuration file in `plugins/HanafubukiPluginUpdater/config.yml`
5. Configure the plugins you want to update in `config.yml`

## Usage

Run the command `/updateplugins` to start the update process. Updates are performed asynchronously and will not block your server.

## Configuration

The plugin uses `config.yml` (auto-generated) and `config.example.yml` for reference:

```yaml
# Global settings
global:
  min_version: "1.0.0"     # Minimum version that plugins must meet to be updated
  max_version: "99.99.99"  # Maximum version that plugins can be updated to

# Plugin update modes
modes:
  include_only: false      # Update only plugins listed in the included list below
  exclude_list:            # List of plugins to exclude from updates
    - "plugin-name-1"
    - "plugin-name-2"

# Plugin list - plugins to update
plugins:
  example-plugin:
    source: "modrinth"     # or "github"
    version: "1.0.0"       # current version
```

## How It Works

1. The plugin reads the configuration file to determine which plugins to update
2. For each plugin, it checks if an update is available from Modrinth or GitHub
3. If an update is found, it downloads the new version to the `update` folder
4. Paper will automatically swap the jar on next restart

## API Requirements

The plugin requires specific User-Agent headers when making requests to APIs:
- **Modrinth**: `"HanafubukiPluginUpdater/1.0.0"`
- **GitHub**: `"HanafubukiPluginUpdater/1.0.0"`

## License

This project is licensed under the GPLv3 license - see the [LICENSE](LICENSE) file for details.

## Development

To build this plugin:

1. Clone the repository
2. Run `./gradlew build` 
3. The built jar will be in `build/libs/`

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.