# plugin-attendance

Плагин посещаемости для звонков OpenVidu/LiveKit. Показывает текущих и вышедших участников, время входа и выхода, длительность присутствия и состояние подключения к событиям звонка.

Backend — один Gradle-модуль на Java 25 и Spring Boot. React/TypeScript-интерфейс собирается вместе с backend и раздаётся из одного исполняемого JAR. Текущая версия плагина — `1.0.1`, зависимость Java SDK — `pluginsdk:plugin-sdk:1.1.0`.

## Как работает активация

1. Клиент запрашивает активацию звонка через `POST /v1/activations` в Plugin Manager.
2. Менеджер получает манифест из Plugin Registry, проверяет `health-url` и доставляет контекст с `activationId`, `activationSecret` и `callId` на `POST /control` плагина.
3. Backend создаёт отдельный SDK-клиент для активации и подписывается на события `participant.joined`, `participant.left`, `call.ended` в StreamAPI. SDK передаёт данные активации для проверки прав.
4. После SDK `onConnected()` плагин отвечает на `/control` кодом 200. По умолчанию ожидание ограничено четырьмя секундами.
5. Страница плагина читает снимки участников через HTTP API. Открытие и закрытие страницы не управляет подпиской.

На один звонок допускается одна одновременно работающая активация attendance. Повтор одинакового control-запроса использует существующую подписку; изменение параметров того же `activationId` возвращает 409. После неудачного запуска тот же запрос может начать новую попытку.

[Подробный контракт `/control`, ответы и ошибки](docs/activation.md).

## Сборка и локальный запуск

Нужны JDK 25 и доступ к GitHub Packages для скачивания SDK. Gradle Wrapper и версия Node.js для сборки интерфейса настроены в проекте; Gradle скачивает Node.js автоматически.

### Доступ к SDK

Добавьте credentials в пользовательский `~/.gradle/gradle.properties` (Windows: `%USERPROFILE%\.gradle\gradle.properties`):

```properties
gpr.user=YOUR_GITHUB_LOGIN
gpr.key=YOUR_GITHUB_TOKEN
```

Используется GitHub personal access token (classic) с `read:packages` и доступом к пакету SDK. Credentials хранятся вне репозитория. Вместо Gradle properties можно передать `GITHUB_USERNAME` и `GITHUB_TOKEN`; также поддерживаются `GITHUB_ACTOR` и `GH_TOKEN`.

Версия SDK закреплена в [каталоге зависимостей](backend/gradle/libs.versions.toml). Исходники SDK для сборки не требуются.

### Запуск приложения

Из корня репозитория `plugins`:

```bash
cd plugin-attendance/backend
./gradlew build
java -jar build/libs/plugin-attendance.jar --spring.profiles.active=local
```

В PowerShell вместо `./gradlew` используйте `.\gradlew.bat`. Команды запуска выполняются из `backend`, чтобы Spring Boot нашёл внешний профиль `config/application-local.yml`.

`build` выполняет тесты, собирает интерфейс и создаёт `backend/build/libs/plugin-attendance.jar`. Вместо запуска JAR можно использовать:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

Локально backend и интерфейс доступны на `http://127.0.0.1:18081`. Проверка запуска (в PowerShell используйте `curl.exe`):

```bash
curl --fail-with-body http://127.0.0.1:18081/actuator/health
curl --fail-with-body http://127.0.0.1:18081/api/info
```

Healthcheck подтверждает работу приложения. Подписка на звонок появляется после команды активации; до неё запрос состава возвращает 404.

### Разработка интерфейса

Для отдельного запуска Vite нужен установленный Node.js 24. В другом терминале из каталога `plugin-attendance`:

```bash
cd frontend
npm ci
npm run dev
```

Откройте `http://localhost:5173/?callId=RM_REAL_CALL_ID`. Vite проксирует `/api` на локальный backend `127.0.0.1:18081`; backend запускается с профилем `local`.

## Профили и настройки

Общие параметры находятся в [application.yml](backend/src/main/resources/application.yml). Профиль выбирается явно через `--spring.profiles.active` или `SPRING_PROFILES_ACTIVE`.

| Профиль | Файл | HTTP | StreamAPI | TLS |
|---|---|---|---|---|
| `local` | [config/application-local.yml](backend/config/application-local.yml) | `127.0.0.1:18081` | `calls.vareonix.cc:443` | Да |
| `prod` | [application-prod.yml](backend/src/main/resources/application-prod.yml) | `0.0.0.0:8081` | `nginx:19090` | Нет, внутренняя Docker-сеть |
| `stage` | [application-stage.yml](backend/src/main/resources/application-stage.yml) | `0.0.0.0:8081` | `nginx-stage:19090` | Нет, внутренняя Docker-сеть |

Локальный профиль находится вне ресурсов и не попадает в JAR или Docker-образ. Серверные профили рассчитаны на Docker-сеть соответствующего окружения. Deployer задаёт `SPRING_PROFILES_ACTIVE=prod` или `stage` при запуске контейнера.

| Параметр | Значение по умолчанию | Назначение |
|---|---|---|
| `attendance.activation.start-timeout` | `4s` | Ожидание подключения при активации; должно быть короче HTTP-таймаута Plugin Manager |
| `attendance.streamapi.target` | Из профиля | Адрес gRPC StreamAPI |
| `attendance.streamapi.plaintext` | Из профиля | Подключение без TLS |
| `attendance.streamapi.metadata` | Пустая map | Дополнительные gRPC-заголовки |
| `attendance.tracking.max-calls` | `100` | Максимальное число хранимых трекеров |
| `attendance.tracking.failed-retention` | `1m` | Хранение завершённых и отказавших трекеров |
| `attendance.tracking.cleanup-interval` | `30s` | Период очистки трекеров |
| `attendance.tracking.ignored-participant-prefixes` | `streamapi-`, `EG_` | Служебные участники, исключаемые из учёта |

`activationId` и `activationSecret` поступают в control-сообщении от Plugin Manager. SDK использует их для авторизации подписки; глобальный ключ менеджера плагину не нужен.

## Проверка на звонке

### Через серверный Plugin Manager

Для серверного экземпляра сначала зарегистрируйте [манифест версии 1.0.1](manifests/plugin-attendance-1.0.1-manifest-1.1.json) в Plugin Registry и разверните соответствующую сборку плагина.

Создайте звонок, получите его `callId` и отправьте запрос (пример для Bash):

```bash
curl --fail-with-body --request POST 'https://calls.vareonix.cc/v1/activations' \
  --header 'Content-Type: application/json' \
  --data '{
    "callId": "RM_REAL_CALL_ID",
    "pluginId": "attendance",
    "pluginVersion": "1.0.1",
    "pluginRegistryId": "local",
    "requestedBy": "manual",
    "idempotencyKey": "REPLACE_WITH_UNIQUE_KEY"
  }'
```

`callId` — SID комнаты LiveKit вида `RM_...`. Для новой активации задавайте новый `idempotencyKey`; повтор того же запроса с прежним ключом возвращает прежний `activationId`.

По возвращённому ID проверьте состояние менеджера и состав звонка:

```bash
curl --fail-with-body https://calls.vareonix.cc/v1/activations/ACTIVATION_ID
curl --fail-with-body https://calls.vareonix.cc/apps/plugin-attendance/api/calls/RM_REAL_CALL_ID/participants
```

Ожидаются `ACTIVE` у Plugin Manager и `TRACKING` в API плагина. Менеджер доставляет секрет непосредственно backend плагина; вручную придумывать его не нужно.

Откройте страницу:

```text
https://calls.vareonix.cc/apps/plugin-attendance/?callId=RM_REAL_CALL_ID
```

Затем выполните вход и выход проверяемого участника: списки `present` и `left` должны обновиться.

### Локальный плагин с серверным StreamAPI

Профиль `local` подключается к серверному StreamAPI по TLS. Для доставки команды от серверного Plugin Manager локальный backend должен быть доступен ему по HTTPS с маршрутами `/actuator/health` и `/control`. Для такого запуска создайте отдельную тестовую версию манифеста с адресом локального экземпляра.

[Инструкция локальной проверки](docs/manual-sdk-tracking.md). Пример прямого вызова `/control` приведён в [контракте активации](docs/activation.md); для него нужны ID и секрет существующей активации из Plugin Manager.

После активации локальная страница доступна по адресу `http://127.0.0.1:18081/?callId=RM_REAL_CALL_ID`. Необязательные параметры `roomId` и `participantId` задают имя комнаты внизу страницы и выделение текущего участника. Подписанный контекст запуска пока не реализован.

## HTTP API и состояния

Пути указаны относительно backend. На сервере к ним добавляется префикс `/apps/plugin-attendance`; NGINX удаляет его перед передачей запроса в приложение.

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/` | Интерфейс плагина, `callId` передаётся в query string |
| POST | `/control` | Запуск активации с ожиданием подключения к StreamAPI |
| GET | `/api/info` | Источник событий, адрес StreamAPI и версия приложения |
| GET | `/api/calls/{callId}/participants` | Состояние подписки, участники и перерывы связи |
| GET | `/actuator/health` | Healthcheck приложения |
| GET | `/actuator/info` | Информация о сборке Spring Boot |

Запрос участников читает уже созданный трекер. Для неизвестного звонка возвращается 404. Снимок содержит `present`, `left`, `trackingSince`, `endedAt`, `gaps`, `serverTime` и состояние подписки:

| Состояние плагина | Значение |
|---|---|
| `CONNECTING` | Первая подписка ещё не установлена |
| `TRACKING` | Подписка активна |
| `RECONNECTING` | Связь потеряна после начала учёта; SDK переподключается |
| `ENDED` | Получено событие завершения звонка |
| `FAILED` | Подписка завершилась ошибкой |

Это состояния трекера плагина. Состояние активации платформы читается отдельно через Plugin Manager.

Начальный состав пока не обрабатывается: `initialContext` принимается, но не применяется; актуальный манифест задаёт `initial-state: []`. Участник, вошедший до подписки, появится после нового события: повторного входа либо выхода с неизвестным временем входа. Снимок хранит последнее известное состояние каждого участника.

После разрыва соединения события за перерыв могут быть потеряны; перерывы отражаются в `gaps` и интерфейсе. Подписка закрывается по `call.ended`, окончательной ошибке SDK или при завершении приложения. Завершённые и отказавшие трекеры удаляются по настройкам retention и cleanup. HTTP-команды ручной остановки нет.

Состояние хранится в памяти одного процесса. После перезапуска оно теряется, а для возобновления подписки менеджер должен повторно доставить команду активации. Развёртывание рассчитано на одну реплику.

## Манифесты и выпуск

Шаблон имени: `plugin-{pluginId}-{pluginVersion}-manifest-{manifestVersion}.json`. Версии плагина и схемы берутся из полей `plugin-version` и `manifest-version` внутри JSON.

| Версия плагина | Манифест |
|---|---|
| `1.0.1` | [plugin-attendance-1.0.1-manifest-1.1.json](manifests/plugin-attendance-1.0.1-manifest-1.1.json) — текущий выпуск |
| `0.1.0` | [plugin-attendance-0.1.0-manifest-1.1.json](manifests/plugin-attendance-0.1.0-manifest-1.1.json) — историческая версия |

Содержимое существующих манифестов неизменяемо. Для новой версии создаётся отдельный файл и отдельная запись версии в Plugin Registry.

[Dockerfile](Dockerfile) упаковывает готовый `backend/build/libs/plugin-attendance.jar`. Для ручной сборки сначала выполните Gradle `build` в `backend`, затем `make image-build` в `plugin-attendance` (нужны Docker и Make). Профиль выбирается при запуске контейнера.

[Workflow plugin-attendance](../.github/workflows/plugin-attendance.yml) на каждой ветке собирает и тестирует JAR. После push в `main` он также публикует образ `ghcr.io/dwspurpleteam/attendance:<commit SHA>` и отправляет событие в deployer. Workflow `images` в deployer создаёт PR с новым тегом образа.

В CI Gradle использует `GITHUB_ACTOR` и `GITHUB_TOKEN`; репозиторию `plugins` нужен доступ к Maven-пакету SDK. Для отправки события в deployer общему workflow требуется секрет `DEPLOYER_TOKEN`.

Изменения deployer сначала объединяются с `dev`, после проверки — с `main` для выкладки в prod. Профиль `stage` подготовлен, но включение stage-сервиса управляется отдельно в deployer. При первом переходе на серверные профили сначала обновляется образ плагина, затем параметры его запуска. [Порядок обновления и серверная проверка](https://github.com/dwspurpleteam/deployer/blob/dev/docs/attendance-deployment.md).

## Тесты и структура проекта

Из `backend`:

```bash
./gradlew test
```

Тесты проверяют обработку участников, повторы активации, ошибки и таймауты `/control`, работу SDK с тестовым gRPC-сервером и загрузку профилей. Тестовые источники событий и контроллеры находятся только в `src/test`. Отчёт: `backend/build/reports/tests/test/index.html`.

```text
backend/
  config/                       Внешний профиль local
  src/main/java/ru/itmo/plugins/attendance/
    PluginAttendanceApplication.java
    web/                        HTTP-контроллеры и ошибки API
    configuration/              Spring-конфигурация и свойства
    service/
      activation/               Контракт и данные активации
      events/                   Интерфейс источника и типы событий
      streamapi/                Адаптер Java SDK
      tracking/                 Состояние звонка и участников
  src/main/resources/           Общие настройки и профили prod/stage
  src/test/                     Тесты и gRPC fixtures
frontend/                       React/TypeScript-интерфейс
manifests/                      Манифесты версий плагина
```

Java SDK используется в `service.streamapi`; трекеры работают через интерфейс `EventSource`. Точка входа приложения — `PluginAttendanceApplication`.
