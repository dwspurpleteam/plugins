# Плагины для звонков

Исходники плагинов платформы. Каждый плагин — отдельный проект в папке `plugin-<имя>` со своей сборкой и README.

| Плагин | Что делает |
| --- | --- |
| [plugin-attendance](plugin-attendance) | Посещаемость: кто сейчас в звонке и кто из него вышел |

## CI

Каждый плагин собирается своим workflow в GitHub Actions: `.github/workflows/plugin-<имя>.yml`. Он запускается на каждый пуш в любую ветку, а вручную — кнопкой Run workflow на вкладке Actions.

Плагины на Gradle используют общий workflow `.github/workflows/gradle-plugin.yml`: он ставит JDK, кэширует зависимости и запускает `./gradlew build`, то есть тесты и сборку jar. Если тесты упали, отчёты лежат в артефакте `test-reports` у запуска.

Чтобы подключить новый плагин на Gradle, скопируйте `plugin-attendance.yml` в `plugin-<имя>.yml` и замените в нём имя плагина и `project-dir` — папку, где лежит `gradlew`. Файл `gradlew` должен быть исполняемым в git: `git add --chmod=+x <путь>/gradlew`.
