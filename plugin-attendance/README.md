# plugin-attendance

Плагин посещаемости для звонков OpenVidu/LiveKit. Backend подписывается на события через Java SDK, хранит состояние участников в памяти и предоставляет его интерфейсу. Запуск выполняется командой Plugin Manager, независимо от открытия страницы.

## Сборка и локальный запуск

Нужен JDK 25. Из `backend`:

```powershell
.\gradlew.bat build
java -jar build/libs/plugin-attendance.jar --spring.profiles.active=local
```

Gradle собирает backend, frontend и исполняемый `build/libs/plugin-attendance.jar`. Java SDK автоматически скачивается из GitHub Packages как Maven-зависимость; отдельный checkout SDK не нужен. Версия закреплена в `backend/gradle/libs.versions.toml`.

Один раз настройте доступ к GitHub Packages в пользовательском `~/.gradle/gradle.properties` (Windows: `%USERPROFILE%\.gradle\gradle.properties`):

```properties
gpr.user=YOUR_GITHUB_LOGIN
gpr.key=YOUR_GITHUB_TOKEN
```

Для Maven registry GitHub используется personal access token (classic) с `read:packages` и доступом к пакету. Храните эти значения в пользовательской конфигурации Gradle, не в репозитории. Альтернатива — переменные окружения `GITHUB_USERNAME` и `GITHUB_TOKEN` (также поддерживаются `GITHUB_ACTOR` и `GH_TOKEN`). В CI используются стандартные GITHUB_ACTOR и GITHUB_TOKEN из GitHub Actions; репозиторию plugins нужен доступ к пакету SDK через настройки GitHub Packages.

Профиль запуска указывается явно. Локальные команды выполняются из `backend`, чтобы Spring Boot автоматически нашёл внешнюю папку `config/`.

| Файл | Назначение |
|---|---|
| `src/main/resources/application.yml` | Общие настройки приложения, трекера и HTTP API |
| `src/main/resources/application-prod.yml` | Прод: внутренний StreamAPI `nginx:19090`, без TLS внутри Docker-сети |
| `src/main/resources/application-stage.yml` | Stage: внутренний StreamAPI `nginx-stage:19090`, без TLS внутри Docker-сети |
| `config/application-local.yml` | Локальный backend на `127.0.0.1:18081`, серверный StreamAPI по TLS |

Локальный профиль не упаковывается в jar. Серверные профили слушают порт 8081; выбирайте их через `SPRING_PROFILES_ACTIVE=prod` или `stage`. В конфигурации deployer выбор профиля задаётся для каждого окружения. Секреты и параметры доступа передаются извне, в ресурсах их нет.

[Подробности локального запуска](docs/manual-sdk-tracking.md).

## Активация

`POST /control` принимает контекст Plugin Manager: версию схемы, activationId, activationSecret, callId, pluginId, pluginVersion и registryId. На каждый activationId создаётся отдельный SDK-клиент и подписка на `participant.joined`, `participant.left`, `call.ended`.

Успешный ответ HTTP 200 возвращается после подключения к StreamAPI. Таймаут запуска по умолчанию 4 секунды, короче HTTP-таймаута менеджера. При отказе или таймауте ресурсы закрываются, а повтор того же сообщения может повторить неудачный запуск. Повторы работающей активации используют существующую подписку; изменение параметров того же ID возвращает 409. Одновременно допускается одна активация attendance на звонок.

[Контракт, curl и ответы](docs/activation.md). [Коллекция Bruno](bruno/bruno.json) содержит запуск и проверку состояния.

Начальный состав пока не обрабатывается: initialContext принимается для совместимости, но не применяется к трекеру. Учитываются только события после подключения. В манифесте указан `initial-state: []`.

Состояние и дедупликация живут в памяти процесса. После перезапуска серверный менеджер должен заново доставить команду запуска. Долговременного хранения истории здесь нет.

## Интерфейс

После активации откройте:

```text
http://127.0.0.1:18081/?callId=RM_REAL_CALL_ID
```

callId — SID комнаты LiveKit вида `RM_...`, а не имя комнаты или ID из ссылки Meet. Параметры roomId и participantId необязательны: это имя комнаты и идентификатор текущего участника для отображения. Подписанный bridge-контекст пока не реализован.

UI только читает состояние участников. Чтение неизвестного звонка возвращает 404 и не создаёт подписку; закрытие страницы не останавливает активацию. При разрыве соединения интерфейс показывает неполноту данных.

## HTTP API

| Метод | Путь | Назначение |
|---|---|---|
| POST | `/control` | Запустить активацию и дождаться подключения |
| GET | `/api/info` | Источник событий, адрес StreamAPI и версия приложения |
| GET | `/api/calls/{callId}/participants` | Состояние активированного звонка и участники |
| GET | `/actuator/health` | Healthcheck приложения |

Протокол команд остановки от Plugin Manager пока не определён. Обработка также заканчивается по call.ended, окончательному отказу SDK или при завершении процесса. Завершённые и отказавшие трекеры удаляются после failed-retention. Активные трекеры не зависят от запросов UI.

## Настройки

| Параметр | По умолчанию | Назначение |
|---|---|---|
| `attendance.activation.start-timeout` | `4s` | Ожидание подключения при активации |
| `attendance.streamapi.target` | Задаётся профилем | Адрес gRPC StreamAPI |
| `attendance.streamapi.plaintext` | `true` в prod/stage, `false` в local | Отключение TLS для внутренней сети |
| `attendance.streamapi.metadata` | пусто | Дополнительные metadata окружения |
| `attendance.tracking.max-calls` | `100` | Ограничение числа трекеров |
| `attendance.tracking.failed-retention` | `1m` | Хранение завершённых и отказавших трекеров |
| `attendance.tracking.cleanup-interval` | `30s` | Период очистки |

ID и секрет активации передаются только в control-сообщении. SDK устанавливает x-call-id и Authorization самостоятельно. Политика переподключения принадлежит SDK; старых настроек reconnect/keepalive в плагине нет.

## Манифест и развёртывание

Манифест выпуска: [plugin-attendance-1.0.1-manifest-1.1.json](manifests/plugin-attendance-1.0.1-manifest-1.1.json). Он объявляет health-url, control-url с путём `/control`, события плагина. Зарегистрируйте его как новую версию `1.0.1`; опубликованную в Registry версию с адресом локального туннеля менять не нужно.

Имена файлов: `plugin-{pluginId}-{pluginVersion}-manifest-{manifestVersion}.json`. Содержимое ранее выпущенных манифестов неизменяемо; для новой версии плагина создаётся отдельный файл.

CI на каждой ветке собирает и тестирует JAR. После push в `main` workflow `plugin-attendance` также публикует образ `ghcr.io/dwspurpleteam/attendance:<commit SHA>` и отправляет событие в deployer. Его workflow `images` открывает PR с новым тегом образа; слияние этого PR запускает обновление сервера через GitSail. Для отправки события общему workflow нужен секрет `DEPLOYER_TOKEN` с доступом к репозиторию deployer.

Dockerfile копирует готовый `backend/build/libs/plugin-attendance.jar`. Для ручной сборки сначала выполните `./gradlew build` в `backend`, затем `make image-build` в `plugin-attendance`. Профиль выбирается при запуске контейнера через `SPRING_PROFILES_ACTIVE`; deployer задаёт `prod` или `stage`.

При первом переходе сначала выпустите новый образ и обновите его тег в deployer, затем примените изменение deployer с профилями. Старые переменные `ATTENDANCE_STREAMAPI_*` позволяют новой сборке работать до переключения на профили. Порядок и серверная проверка описаны в `deployer/docs/attendance-deployment.md`.

Для серверного Plugin Manager локальному экземпляру нужен доступный HTTPS-адрес с маршрутами `/actuator/health` и `/control`. Настройка туннеля и развёртывание выполняются отдельно. Во внешнем nginx префикс `/apps/plugin-attendance/` удаляется перед передачей в приложение.

## Тесты и структура

- `backend/src/main` — Spring Boot приложение, настройки, активации, SDK-адаптер, трекер и HTTP API.
- `backend/src/test` — модульные и интеграционные тесты, проверки профилей, тестовый источник событий и gRPC fixtures.
- `backend/config` — внешние настройки локального запуска.
- `frontend/src` — интерфейс, который читает снимки состояния.

Фейковый источник, `/dev/...` и demo-host не входят в рабочий jar. Интеграционные тесты запускают приложение с тестовыми fixtures; обходы авторизации StreamAPI в рабочем коде отсутствуют.

Пакеты внутри `ru.itmo.plugins.attendance`:

```text
web/                        HTTP-контроллеры и ответы об ошибках
configuration/              Spring-конфигурация и параметры приложения
service/
  activation/               Контракт активации, её учётные данные и ошибки
  tracking/                 Состояние звонка, участники и жизненный цикл трекера
  events/                   Контракт источника событий и внутренние типы событий
  streamapi/                Java SDK, соединения и преобразование событий
```

Java SDK используется только в `service.streamapi`. Учёт участников зависит от интерфейса `EventSource`; HTTP-контроллеры вызывают сервисы, а настройки находятся в `configuration`. Тесты повторяют эту структуру пакетов.
