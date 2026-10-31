<div align="center">

# Hanafubuki Plugin Updater

[![Paper](https://img.shields.io/badge/Paper-1.21.9-blue)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net/)
[![Gradle](https://img.shields.io/badge/Gradle-Kotlin%20DSL-green)](https://gradle.org/)
[![License](https://img.shields.io/badge/License-GPLv3-red)](LICENSE)
[![Modrinth](https://img.shields.io/badge/Источник-Modrinth-purple)](https://modrinth.com/)
[![GitHub](https://img.shields.io/badge/Источник-GitHub-black)](https://github.com/)

**Автоматическое обновление плагинов для Paper-серверов из Modrinth и GitHub**

[English](README.md) | [Конфигурация](docs/configuration.md) | [Безопасность](docs/security.md) | [Архитектура](docs/architecture.md) | [Планы](docs/roadmap.md)

</div>

---

## Как это работает

Hanafubuki Plugin Updater проверяет настроенные плагины на наличие новых версий в Modrinth и GitHub, безопасно скачивает обновления в папку Bukkit update и уведомляет операторов о необходимости перезагрузки.

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Админ запускает /updateplugins                            │
├─────────────────────────────────────────────────────────────┤
│ 2. Для каждого настроенного плагина:                         │
│    ├─ Modrinth: Вычислить SHA-512 установленного JAR         │
│    │         → GET /version_file/{hash} → project_id         │
│    │         → GET /project/{id}/version?loaders=paper       │
│    │         → Проверить SHA-512 + имя в plugin.yml          │
│    └─ GitHub:  GET /repos/{owner}/{repo}/releases/latest     │
│               → Проверить имя в plugin.yml                   │
├─────────────────────────────────────────────────────────────┤
│ 3. Если найдена новая версия:                                │
│    └─ Скачать в plugins/update/{name}.jar.part               │
│       → Атомарный переход в plugins/update/{name}.jar        │
├─────────────────────────────────────────────────────────────┤
│ 4. Рассылка опы: "Plugin X updated, try restarting"         │
├─────────────────────────────────────────────────────────────┤
│ 5. При следующей перезагрузке: Paper подменяет JAR сам       │
└─────────────────────────────────────────────────────────────┘
```

## Основные возможности

| Возможность | Описание |
|-------------|----------|
| **Безопасный поиск в Modrinth** | SHA-512 хеш установленного JAR → нет атак через поиск по имени |
| **Поддержка GitHub Releases** | Формат owner/repo, фильтрация ассетов по шаблону |
| **Два режима обновления** | Include-only или exclude-list (работает в обоих режимах) |
| **Фильтрация версий** | Глобальные и по-плагин лимиты min/max версий |
| **Безопасная установка** | Скачивание в `plugins/update/`, атомарный move, применение при рестарте |
| **Асинхронные операции** | Весь сетевой I/O в фоне через `CompletableFuture` |
| **MiniMessage стилизация** | Настраиваемые цвета, градиенты, форматирование |
| **Уведомления операторов** | Broadcast при успешном обновлении |

## Установка

### Требования
- **Java 21**
- **Paper 1.21.9** (также работает на форках: Purpur, Pufferfish и др.)
- Совместим с Geyser/Floodgate/ViaVersion

### Установка
1. Скачайте последний `.jar` из [Releases](https://github.com/Hanafubuki-minecraft/hanafubuki-plugin-updater/releases/latest)
2. Положите в папку `plugins/` сервера
3. Запустите/перезапустите сервер
4. Отредактируйте `plugins/HanafubukiPluginUpdater/config.yml` (создаётся при первом запуске)
5. Выполните `/updateplugins` для проверки обновлений

### Конфигурация

Скопируйте `config.yml` и настройте:

```yaml
global:
  min_version: "1.0.0"
  max_version: "99.99.99"

modes:
  include_only: false          # true = обновлять только плагины из include_list
  exclude_list: []             # плагины для пропуска (в обоих режимах)
  include_list: []             # плагины для обновления (режим include_only)

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

Полная схема в [Справочнике конфигурации](docs/configuration.md).

## Команды

| Команда | Право | Описание |
|---------|-------|----------|
| `/updateplugins` | `hanafubuki.updater.update` | Проверить и скачать обновления плагинов |
| `/updateplugins reload` | `hanafubuki.updater.update` | Перезагрузить конфигурацию (планируется) |
| `/updateplugins --dry-run` | `hanafubuki.updater.update` | Предпросмотр обновлений без скачивания (планируется) |

По умолчанию: **только для OP**

## Релизы

Сборка и публикация через GitHub Actions при изменении версии:

1. Обновите `version=` в `gradle.properties`
2. `git commit -am "Release vX.Y.Z"`
3. `git tag vX.Y.Z`
4. `git push origin master --tags`
5. GitHub Actions собирает, подписывает и публикует в Releases

### Локальная сборка (для разработки)
```bash
./gradlew build
# Результат: build/libs/hanafubuki-plugin-updater-<version>.jar
```

## Технологический стек

- **Java 21** + **Gradle Kotlin DSL**
- **Paper API** 1.21.9 (compileOnly)
- **Gson** для парсинга JSON
- **SnakeYAML** для конфига (через Bukkit)
- **Modrinth API v2** (labrinth)
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