<div align="center">

# HanafubukiPluginUpdater

**Держит все плагины на Paper-сервере свежими — безопасно, в фоне и не трогая работающие jar-файлы.**

[![Build](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/actions/workflows/release.yml/badge.svg)](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/actions/workflows/release.yml)
[![Release](https://img.shields.io/github/v/release/Hanafubuki-minecraft/hanafubuki-plugin-updater)](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest)
[![License: GPL v3](https://img.shields.io/github/license/Hanafubuki-minecraft/hanafubuki-plugin-updater)](LICENSE)
![Paper](https://img.shields.io/badge/Paper-1.21.9-blue)
![Java](https://img.shields.io/badge/Java-21-orange)

[English](README.md) · Русский

</div>

---

Сделан для сервера **Hanafubuki**, но работает на любом Paper-сервере.
Запускаете `/updateplugins` — новые версии скачиваются в `plugins/update/`, а Paper подменяет их при следующем рестарте.

## Возможности

- 🟢 **Modrinth в первую очередь**: установленный jar определяется по SHA-512, а не поиском по имени, так что похожий проект не подсунуть.
- 🐙 **Релизы GitHub**: последний релиз, нужный файл выбирается по шаблону (`plugin-*.jar`).
- 📦 **Другие источники**: [Hangar](https://hangar.papermc.io), SpigotMC (через Spiget), локальная или сетевая папка, любой JSON REST API с JSONPath.
- 🛡️ **Безопасная установка**: скачивание в `plugins/update/<то же имя>.jar.part`, проверка хеша и `name` в `plugin.yml`, затем атомарное перемещение. Работающие jar никогда не перезаписываются.
- ⚡ **Полностью асинхронно**: весь сетевой I/O вне основного потока, плагины проверяются параллельно; при ошибках — повторы с нарастающей задержкой.
- 🎯 **Фильтры версий**: глобальные `min_version` / `max_version`, можно переопределить для каждого плагина; Modrinth фильтруется по загрузчику и версии игры.
- 📋 **Два режима**: обновлять только `include_list` или всё, кроме `exclude_list` (исключения работают в обоих).
- 🔍 **Автообнаружение**: находит установленные плагины, которых нет в конфиге, и добавляет их.
- 🧪 **Пробный запуск**: показывает, что обновится, ничего не скачивая.
- 📢 **Уведомление опов**: «Plugin X updated, try restarting», когда обновление поставлено в очередь.
- 📊 **Наблюдаемость**: `/updateplugins metrics` (в том числе в формате Prometheus), JSON-логи и HTTP-эндпоинт `/health` по желанию.

## Установка

1. Скачайте последний `.jar` из [Releases](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest).
2. Положите его в `plugins/`.
3. Перезапустите сервер — появится `plugins/HanafubukiPluginUpdater/config.yml`.
4. Добавьте плагины в раздел `plugins:` (или выполните `/updateplugins discover --auto`).
5. Выполните `/updateplugins` и перезапустите сервер, когда придёт уведомление.

## Команды

| Команда | Описание | Право |
|---|---|---|
| `/updateplugins` | Проверить все плагины и скачать обновления | `hanafubuki.updater.update` (op) |
| `/updateplugins dry-run` | Показать, что обновится, ничего не скачивая (также `--dry-run`, `-d`) | `hanafubuki.updater.update` |
| `/updateplugins reload` | Перезагрузить `config.yml` | `hanafubuki.updater.update` |
| `/updateplugins discover` | Список установленных плагинов, которых нет в конфиге | `hanafubuki.updater.update` |
| `/updateplugins discover add [плагин]` | Добавить в конфиг все (или один) найденные плагины | `hanafubuki.updater.update` |
| `/updateplugins discover --auto` | Запустить автообнаружение сейчас | `hanafubuki.updater.update` |
| `/updateplugins metrics [--prometheus]` | Проверки, обновления, загрузки и ошибки с момента запуска | `hanafubuki.updater.update` |

Автодополнение работает для подкоманд и имён найденных плагинов.

## Настройка

`plugins/HanafubukiPluginUpdater/config.yml`, коротко:

```yaml
global:
  min_version: "1.0.0"
  max_version: "99.99.99"
  auto_discover: false
  auto_discover_on_startup: false
  auto_update_config: true     # записывать новую версию в конфиг после обновления

modes:
  include_only: false          # true = только плагины из include_list
  include_list: []
  exclude_list: []             # пропускаются в обоих режимах

modrinth:
  # loaders: ["paper"]
  # game_versions: ["1.21.9"]

github:
  # token: "ghp_..."           # необязательно, повышает лимит запросов к API

plugins:
  "LuckPerms":
    source: "modrinth"
    version: "5.4.120"
  "Floodgate":
    source: "github"
    version: "2.4.0"
    repo: "GeyserMC/Floodgate"
    asset_pattern: "floodgate-spigot.jar"
```

### Источники

| `source` | Обязательные поля | Необязательные | Проверка |
|---|---|---|---|
| `modrinth` | `version` | `project_id` (ID или slug) | SHA-512 + имя в `plugin.yml` |
| `github` | `version`, `repo` (`owner/repo`) | `asset_pattern` | имя в `plugin.yml` |
| `hangar` | `version`, `project_slug` (`owner/slug`) | — | SHA-256 + имя в `plugin.yml` |
| `spigotmc` | `version`, `resource_id` или `resource_name` | — | имя в `plugin.yml` (Spiget не отдаёт хеши) |
| `local` | `version`, `path` (папка или файл) | `pattern`, `checksum` | SHA-256 / файлы `.sha256`, `.sha512` рядом + имя в `plugin.yml` |
| `custom` | `version`, `url` | `version_path`, `download_url_path`, `checksum_path`, `headers`, `auth` | SHA-256 (если есть) + имя в `plugin.yml` |

У каждого плагина можно задать свои `min_version` / `max_version`.

Полный справочник со всеми параметрами и значениями по умолчанию: [`docs/config.example.yml`](docs/config.example.yml) и [`docs/configuration.md`](docs/configuration.md).

## Совместимость

| Платформа | Статус |
|---|---|
| Paper 1.21.9 | ✅ Поддерживается |
| Purpur, Pufferfish и другие форки Paper | ✅ Должно работать |
| Geyser / Floodgate, ViaVersion | ✅ Поддерживаются (и могут им обновляться) |
| Spigot / CraftBukkit | ❌ Не поддерживается |

## Документация

- [Архитектура](docs/architecture.md) — компоненты, поток данных, потоки
- [Настройка](docs/configuration.md) — все параметры, режимы и фильтры версий
- [API](docs/api.md) — как идут запросы к Modrinth и GitHub
- [Безопасность](docs/security.md) — модель угроз и проверки
- [Разработка](docs/development.md) — сборка, тесты, релизы

## Сборка

Проект собирает GitHub Actions — локально собирать не нужно.
Если всё же хочется: Java 21.

```bash
./gradlew build
# → build/libs/hanafubuki-plugin-updater-<version>.jar
```

## Релиз

Версия плагина хранится в одном месте — [`gradle.properties`](gradle.properties):

```properties
version=1.2.2
```

Поднимите её и запушьте в `master` → GitHub Actions соберёт jar и опубликует релиз `v<version>` с автоматическими заметками.

## Лицензия

[GNU General Public License v3.0](LICENSE) © ByteMe6

<div align="center">

*Часть экосистемы [Hanafubuki](https://github.com/Hanafubuki-minecraft)*

</div>
