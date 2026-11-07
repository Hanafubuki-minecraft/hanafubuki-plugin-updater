<div align="center">

# HanafubukiPluginUpdater

**Держит все плагины на Paper-сервере свежими — безопасно, в фоне и не трогая работающие jar-файлы.**

[![Build](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/actions/workflows/build.yml/badge.svg)](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Hanafubuki-minecraft/hanafubuki-plugin-updater)](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest)
[![License: GPL v3](https://img.shields.io/github/license/Hanafubuki-minecraft/hanafubuki-plugin-updater)](LICENSE)
![Paper](https://img.shields.io/badge/Paper-1.21.9-blue)
![Java](https://img.shields.io/badge/Java-21-orange)

[English](README.md) · Русский

</div>

---

Сделан для сервера **Hanafubuki**, но работает на любом Paper-сервере.
Новые версии скачиваются в папку обновлений Bukkit (`plugins/update/`) под именем установленного jar,
а Paper подменяет их при следующем рестарте.

## Возможности

- **Modrinth в первую очередь** — установленный jar определяется по SHA-512, а не поиском по имени, так что похожий проект не подсунуть
- **Релизы GitHub** — последний релиз или пре-релизы, нужный jar выбирается по шаблону (`EssentialsX-2*.jar`) и никогда не угадывается, контрольные суммы из digest GitHub, отдельных файлов или заметок к релизу, проверка подписи по желанию, приватные репозитории с токеном
- **Другие источники** — [Hangar](https://hangar.papermc.io) (каналы, SHA-256), SpigotMC через Spiget, локальная или сетевая папка, любой JSON-эндпоинт с JSON-путями и авторизацией
- **Безопасная установка** — скачивание во временный файл, проверка хеша, `name` плагина и его зависимостей, затем атомарное перемещение в `plugins/update/`. Работающие jar никогда не перезаписываются
- **Резервные копии и откат** — старый jar сохраняется; `/updateplugins rollback` возвращает его при следующем рестарте
- **Уязвимости** — GitHub security advisories для каждого плагина с `repo`, в консоли, в чате и в Discord
- **Расписание** — каждые N минут, ежедневно или еженедельно в заданное время, при запуске или только проверка
- **Уведомления** — «Plugin X updated, try restarting» для опов, напоминание при входе, вебхук Discord, общий JSON-вебхук
- **GUI** — `/updateplugins gui`: состояние плагинов с первого взгляда, проверка / обновление / откат одним кликом
- **Гибкие права** — отдельное право на каждую подкоманду, удобно для LuckPerms
- **Полностью асинхронно** — весь сетевой I/O вне основного потока, плагины проверяются параллельно, повторы с нарастающей задержкой, отмена через `/updateplugins stop`
- **Наблюдаемость** — статистика по каждому плагину, `/updateplugins metrics`, JSON-логи, эндпоинты `/health` и `/metrics` для Prometheus

## Установка

1. Скачайте последний `.jar` из [Releases](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest).
2. Положите его в `plugins/` и перезапустите сервер — появится `plugins/HanafubukiPluginUpdater/config.yml`.
   Плагинов в нём пока нет.
3. Добавьте плагины в раздел `plugins:` или выполните `/updateplugins discover`, а при
   `global.auto_discover: true` — `/updateplugins discover add`.
4. `/updateplugins reload`, затем `/updateplugins check`, чтобы увидеть, что изменится.
5. `/updateplugins update` и рестарт, когда придёт уведомление.

## Команды

`/updateplugins` — псевдонимы `/pluginupdater`, `/hpu`.

| Команда | Описание | Право |
|---|---|---|
| `/updateplugins` | Обновить все плагины (то же, что `update`) | `hanafubuki.updater.update` |
| `/updateplugins check [плагины…]` | Проверить обновления, ничего не скачивая (также `--dry-run`, `-d`, `dry-run`) | `hanafubuki.updater.check` |
| `/updateplugins update [all\|плагины…] [--dry-run]` | Скачать обновления, применятся при рестарте | `hanafubuki.updater.update` |
| `/updateplugins stop` | Остановить текущую проверку | `hanafubuki.updater.stop` |
| `/updateplugins status` | Идёт ли проверка, ожидающие обновления, расписание, последний запуск | `hanafubuki.updater.status` |
| `/updateplugins stats [плагин]` | Проверки, обновления, ошибки, средний интервал обновлений | `hanafubuki.updater.stats` |
| `/updateplugins metrics [--prometheus]` | Счётчики с момента запуска сервера | `hanafubuki.updater.metrics` |
| `/updateplugins backups <плагин>` | Список сохранённых прошлых версий | `hanafubuki.updater.rollback` |
| `/updateplugins rollback <плагин> [версия]` | Вернуть прошлую версию при следующем рестарте | `hanafubuki.updater.rollback` |
| `/updateplugins unstage <плагин>` | Убрать скачанное обновление до рестарта, снова включить обновления после отката | `hanafubuki.updater.rollback` |
| `/updateplugins discover [add] [плагин]` | Найти установленные плагины, которых нет в конфиге (и добавить их) | `hanafubuki.updater.discover` |
| `/updateplugins notify test` | Отправить тестовое сообщение в Discord / вебхук | `hanafubuki.updater.notifytest` |
| `/updateplugins gui` | Открыть меню | `hanafubuki.updater.gui` |
| `/updateplugins reload` | Перезагрузить `config.yml` | `hanafubuki.updater.reload` |

Каждой подкоманде нужно ещё право `hanafubuki.updater.use`. Автодополнение предлагает только то, что вам доступно.
Подробнее и про GUI: [docs/index.md](docs/index.md).

## Права

Все права по умолчанию у опов; `hanafubuki.updater.*` даёт всё. Кроме прав из таблицы выше,
`hanafubuki.updater.notify` получает в игре сообщения об обновлениях и уязвимостях.

С LuckPerms:

```text
# модераторы могут проверять, но не устанавливать
/lp group moderator permission set hanafubuki.updater.use true
/lp group moderator permission set hanafubuki.updater.check true

# всё одному игроку
/lp user Steve permission set hanafubuki.updater.* true

# забрать одно право у админов с опкой
/lp group admin permission set hanafubuki.updater.update false
```

## Настройка

`plugins/HanafubukiPluginUpdater/config.yml`, коротко:

```yaml
global:
  min_version: ""              # установленные версии ниже этой не трогаются
  max_version: ""              # версии выше этой никогда не ставятся
  auto_update_config: true     # записывать новую версию в запись плагина после обновления
  parallelism: 4

modes:
  include_only: false          # true = только плагины из include_list
  include_list: []
  exclude_list: []             # пропускаются в обоих режимах

schedule:
  mode: daily                  # off | interval | daily | weekly
  time: "04:30"
  check_only: false

github:
  token: "${GITHUB_TOKEN}"     # необязательно, берётся из переменной окружения

notifications:
  discord:
    enabled: true
    webhook_url: "${DISCORD_WEBHOOK_URL}"

plugins:
  LuckPerms:
    source: modrinth
  ViaVersion:
    source: hangar
    project_slug: ViaVersion
    repo: ViaVersion/ViaVersion   # включает проверку уязвимостей
  ProtocolLib:
    source: github
    repo: dmulloy2/ProtocolLib
    asset_pattern: "ProtocolLib.jar"
```

### Источники

| `source` | Обязательно | Необязательно | Проверка |
|---|---|---|---|
| `modrinth` | — | `project_id`, `loaders`, `game_versions`, `version_types` | SHA-512 |
| `github` | `repo` (`owner/repo`) | `asset_pattern`, `include_prereleases`, `signatures` | digest GitHub / файл рядом / список сумм / заметки к релизу, если есть; подписи |
| `hangar` | `project_slug` | `channel` | SHA-256 |
| `spigotmc` | `resource_id` или `resource_name` | — | нет (Spiget не отдаёт хеши) |
| `local` | `path` (папка или файл) | `pattern`, `checksum` | файл `.sha512` / `.sha256` / `.sha1` рядом или `checksum` |
| `custom` | `url` | `version_path`, `download_url_path`, `checksum_path`, `checksum`, `headers`, `auth`, `allow_http` | сумма от эндпоинта, если есть |

Кроме того, любой скачанный файл должен быть jar-плагином с тем же `name`, что у установленного. В каждой записи
можно задать `min_version`, `max_version` и `repo` (уязвимости с GitHub). `version` — только для справки;
установленная версия берётся у сервера.

Полный справочник: [docs/configuration.md](docs/configuration.md). Заполненный пример со всеми источниками:
[docs/config.example.yml](docs/config.example.yml).

## Совместимость

| Платформа | Статус |
|---|---|
| Paper 1.21.9 | ✅ Поддерживается |
| Purpur, Pufferfish и другие форки Paper | ✅ Должно работать |
| Geyser / Floodgate, ViaVersion | ✅ Поддерживаются (и могут им обновляться) |
| Spigot / CraftBukkit | ❌ Не поддерживается (нужен Paper API) |

## Документация

Документация на английском:

- [Обзор](docs/index.md) — быстрый старт, команды, права, GUI
- [Настройка](docs/configuration.md) — все параметры, источники, форматы уведомлений, health-эндпоинт
- [Архитектура](docs/architecture.md) — компоненты, ход обновления, потоки, бэкапы и откат
- [Источники и HTTP](docs/api.md) — как опрашивается и проверяется каждый источник
- [Безопасность](docs/security.md) — модель угроз и проверки
- [Разработка](docs/development.md) — сборка, тесты, CI, релизы
- [Планы](docs/roadmap.md) — что готово, список изменений, идеи

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
version=1.5.1
```

Поднимите её и запушьте в `master` → GitHub Actions соберёт jar и опубликует релиз `v<version>` с автоматическими заметками.

## Лицензия

[GNU General Public License v3.0](LICENSE) © ByteMe6

<div align="center">

*Часть экосистемы [Hanafubuki](https://github.com/Hanafubuki-minecraft)*

</div>
