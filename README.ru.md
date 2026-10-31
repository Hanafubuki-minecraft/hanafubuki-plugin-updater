<div align="center">

# Hanafubuki Plugin Updater

**Автоматическое обновление плагинов для Paper-серверов из Modrinth и GitHub.**

[English](README.md) · Русский

</div>

## Как это работает

- **Modrinth**: Вычисляет SHA-512 установленного JAR → `GET /version_file/{hash}` → project ID → `GET /project/{id}/version?loaders=paper&game_versions=["1.21.9"]` → проверяет хеш + имя в `plugin.yml`. Никакого поиска по имени, никакого спуфинга.
- **GitHub**: `GET /repos/{owner}/{repo}/releases/latest` → подбирает ассет по шаблону → проверяет имя в `plugin.yml`.
- **Безопасная установка**: Скачивает в `plugins/update/{name}.jar.part` → атомарный move в `plugins/update/{name}.jar` → Paper подменяет при следующем рестарте. Никогда не перезаписывает работающие JAR.
- **Асинхронность**: Весь сетевой I/O уходит в фоновые потоки через `CompletableFuture`.
- **Уведомления**: Рассылает опы «Plugin X updated, try restarting», когда обновления готовы.

## Установка

### Требования

- **Java 21**
- **Paper 1.21.9** (также Purpur, Pufferfish, Folia)
- Совместим с Geyser/Floodgate/ViaVersion

### Установка

1. Скачайте последний `.jar` из [Releases](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest)
2. Положите в папку `plugins/` сервера
3. Запустите/перезапустите сервер
4. Отредактируйте `plugins/HanafubukiPluginUpdater/config.yml` (создаётся при первом запуске из [config.example.yml](docs/config.example.yml))
5. Выполните `/updateplugins` для проверки обновлений

## Конфигурация

Конфиг генерируется при первом запуске из встроенного примера.  
Полный прокомментированный справочник — [docs/config.example.yml](docs/config.example.yml).

**Основные секции:**

```yaml
global:
  min_version: "1.0.0"
  max_version: "99.99.99"

modes:
  include_only: false          # true = обновлять только плагины из include_list
  exclude_list: []             # плагины для пропуска (в обоих режимах)
  include_list: []             # плагины для обновления (режим include_only)

modrinth:
  # timeout: 10
  # download_timeout: 60
  # loaders: ["paper"]
  # game_versions: ["1.21.9"]

github:
  # timeout: 10
  # download_timeout: 60
  # token: "ghp_..."           # опционально, повышает лимит запросов
  # default_branch: "main"

console:
  mini_message: true
  colors:
    info: "<green>"
    warning: "<yellow><bold>"
    error: "<red><italic>"
    success: "<aqua><bold>"
    header: "<gradient:gold:yellow>"

plugins:
  "WorldEdit":
    source: "modrinth"
    version: "7.3.12"
    # project_id: "worldedit"  # опционально: явный ID/slug Modrinth

  "Paper":
    source: "github"
    version: "1.21.9-R0.1"
    repo: "PaperMC/Paper"
    # asset_pattern: "paper-*.jar"
```

### Поля записи плагина

| Поле | Modrinth | GitHub | Описание |
|------|:--------:|:------:|----------|
| `source` | ✓ обязательно | ✓ обязательно | `"modrinth"` или `"github"` |
| `version` | ✓ обязательно | ✓ обязательно | Текущая установленная версия (для сравнения) |
| `project_id` | опционально | — | ID/slug проекта Modrinth (переопределяет поиск по хешу) |
| `repo` | — | ✓ обязательно | GitHub `owner/repo` |
| `asset_pattern` | — | опционально | Глоб-шаблон ассета релиза (например, `paper-*.jar`) |
| `min_version` | опционально | опционально | Переопределение глобального минимума |
| `max_version` | опционально | опционально | Переопределение глобального максимума |

## Команды

| Команда | Право | Описание |
|---------|-------|----------|
| `/updateplugins` | `hanafubuki.updater.update` (OP) | Проверить и скачать обновления плагинов |
| `/updateplugins reload` | `hanafubuki.updater.update` (OP) | Перезагрузить конфигурацию (планируется) |
| `/updateplugins --dry-run` | `hanafubuki.updater.update` (OP) | Предпросмотр обновлений без скачивания (планируется) |

По умолчанию: **только для OP**

## Релизы

Поднимаешь `version=` в [`gradle.properties`](gradle.properties) и пушишь в `master`.  
GitHub Actions собирает, подписывает и публикует в Releases.

```bash
# Локальная сборка (для разработки)
./gradlew build
# Результат: build/libs/hanafubuki-plugin-updater-<version>.jar
```

## Технологический стек

- **Java 21** + **Gradle Kotlin DSL**
- **Paper API** 1.21.9 (compileOnly)
- **Gson** для парсинга JSON
- **SnakeYAML** для конфига (через Bukkit)
- **Modrinth API v2** (https://api.modrinth.com/v2)
- **GitHub REST API v3**

## Безопасность

Подробности в [документации по безопасности](docs/security.md):
- Идентификация в Modrinth по SHA-512 хешу (без поиска по имени)
- Верификация скачанных файлов (хеш + имя в plugin.yml)
- Безопасная механика обновления (update folder + атомарный move)
- Модель угроз и меры защиты

## Лицензия

**GPLv3** — как и остальные плагины Hanafubuki.

© 2026 **ByteMe6** / **Hanafubuki-minecraft**

---

<div align="center">

*Часть экосистемы [Hanafubuki](https://github.com/Hanafubuki-minecraft)*

</div>