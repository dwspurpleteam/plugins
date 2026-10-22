# plugin-attendance

Плагин посещаемости для звонков. Версия 0.1 показывает, кто сейчас в звонке и кто из него вышел, по событиям [streamapi](https://github.com/dwspurpleteam/streamapi).

```
plugin-attendance/
  frontend/   страница плагина в iframe: React, Vite, TypeScript
  backend/    сервер плагина: Java 25, Spring Boot 4.1; собирает frontend и отдаёт его вместе с API
```

```
iframe в Meet ── GET api/calls/{callId}/participants, раз в 2 с ──► backend (Spring Boot)
                                                                      │ gRPC EventService.Subscribe
                                                                      ▼
                                              вебхуки LiveKit ──► streamapi ──► NATS JetStream
```

- **frontend** — самостоятельный npm-проект. Его сборку `frontend/dist` Gradle кладёт в jar backend, поэтому страницу и API отдаёт один процесс.
- **backend** — самостоятельный Gradle-проект со своим wrapper и двумя модулями: `core` — код плагина, `assembly` — main class, настройки и исполняемый jar. Первый запрос по `callId` подписывает плагин на `participant.joined`, `participant.left` и `call.ended`, состав звонка хранится в памяти. Если 10 минут никто не открывает плагин в звонке, отслеживание останавливается.
- **Источник событий** — `streamapi` (по умолчанию) или `fake`, генератор для разработки без сети.

## Требования

- Java 25 (LTS). Плагин собирается, тестируется и запускается через `bootRun` на JDK 25, какой бы JDK ни запускал Gradle: для самого Gradle подойдёт любой JDK 17+ в `JAVA_HOME` или `PATH`. Установленный JDK 25 Gradle находит сам, а если не найдёт, скачает в `~/.gradle/jdks`. Готовый jar запускается на Java 25 и новее.
- Node.js ставить не нужно: при первой сборке Gradle скачает Node 24 в `backend/.gradle/`.
- Для работы со streamapi на стенде нужна сеть, в которой gRPC (HTTP/2) доходит до `calls.vareonix.cc:443`. Если прокси режет HTTP/2, используйте демо-режим.

## Быстрый старт с демо-данными

Все команды Gradle запускаются из `plugins/plugin-attendance/backend`:

```shell
cd plugins/plugin-attendance/backend

# Windows (PowerShell)
.\gradlew.bat bootRun --args='--attendance.source=fake'

# Linux, macOS
./gradlew bootRun --args='--attendance.source=fake'
```

Первый запуск занимает несколько минут: Gradle скачивает зависимости и Node. Потом откройте одну из страниц:

- <http://localhost:8081/dev-host.html> — плагин в iframe с `sandbox="allow-scripts"`, как в OpenVidu Meet, и кнопки «Вошёл», «Вышел», «Завершить звонок» и «Обрыв связи».
- <http://localhost:8081/?callId=demo> — страница плагина напрямую.

Без кнопок событие можно отправить запросом:

```shell
# bash
curl -X POST localhost:8081/dev/calls/demo/events -H 'Content-Type: application/json' \
  -d '{"type":"participant.joined","participantId":"anna-0123456789abcde"}'

# PowerShell
Invoke-RestMethod -Method Post http://localhost:8081/dev/calls/demo/events -ContentType 'application/json' `
  -Body '{"type":"participant.joined","participantId":"anna-0123456789abcde"}'
```

## С настоящим звонком на стенде

1. Запустите backend без параметров: `.\gradlew.bat bootRun` (Windows) или `./gradlew bootRun`. Он подпишется на streamapi prod по адресу `calls.vareonix.cc:443`.
2. Зайдите в звонок на <https://calls.vareonix.cc/meet/> и узнайте `callId` — это SID комнаты LiveKit вида `RM_…`, а не имя комнаты.
   - Откройте DevTools (F12) → Network, отфильтруйте WS и выберите соединение `rtc?…` → вкладка Messages.
   - Одно из первых входящих бинарных сообщений (JoinResponse) содержит SID. Переключите просмотр сообщения на UTF-8 и найдите `RM_`.
   - Либо скопируйте сообщение как Base64 и выполните в консоли `atob('<base64>').match(/RM_\w+/)[0]`.
3. Откройте <http://localhost:8081/dev-host.html?callId=RM_…>.
4. Заходите в звонок и выходите из него со второй вкладки или с телефона: список меняется через одну–три секунды.

Другие окружения:

- **stage**: включите [профиль](#профили) `stage`: `--args='--spring.profiles.active=stage'`. Он добавляет к подписке cookie окружения stage, без неё запросы уходят в prod.
- **локальный streamapi**: `--attendance.streamapi.target=localhost:19090 --attendance.streamapi.plaintext=true`.

Несколько параметров передаются через пробел: `--args='--server.port=8090 --attendance.source=fake'`.

## Сборка и запуск jar

Из `backend/`:

```shell
.\gradlew.bat build    # тесты, сборка frontend и assembly/build/libs/plugin-attendance.jar
java -jar assembly/build/libs/plugin-attendance.jar --attendance.source=fake
```

## Разработка фронтенда

Для hot reload запустите backend в демо-режиме, а во втором терминале — Vite:

```shell
cd plugins/plugin-attendance/backend; .\gradlew.bat npm_run_dev   # Node, скачанный Gradle
cd plugins/plugin-attendance/frontend; npm run dev                # или свой Node 20.19+ / 22.12+
```

Страница будет на <http://localhost:5173/?callId=demo>, запросы `api/` Vite перенаправляет на `localhost:8081`. Страница `dev-host.html` относится к backend: она работает только на порту 8081 и показывает уже собранный фронтенд.

## Настройки backend

Задаются аргументами `--имя=значение`, переменными окружения (`ATTENDANCE_SOURCE=fake`), в файлах [профилей](#профили) или в `backend/assembly/src/main/resources/application.yaml`. Приоритет убывает в том же порядке.

| Свойство | По умолчанию | Назначение |
| --- | --- | --- |
| `attendance.source` | `streamapi` | Источник событий: `streamapi` или `fake` |
| `server.port` | `8081` | HTTP-порт плагина |
| `attendance.streamapi.target` | `calls.vareonix.cc:443` | Адрес gRPC streamapi; внутри docker-сети стенда `nginx:19090` |
| `attendance.streamapi.plaintext` | `false` | Подключение без TLS |
| `attendance.streamapi.metadata.<заголовок>` | — | Дополнительные заголовки подписки, например cookie для stage |
| `attendance.tracking.idle-timeout` | `10m` | Через сколько без запросов UI отслеживание звонка останавливается |
| `attendance.tracking.max-calls` | `100` | Сколько звонков отслеживается одновременно |
| `attendance.tracking.ignored-participant-prefixes` | `streamapi-`, `EG_` | Служебные участники: скрытый получатель streamapi, запись |
| `attendance.fake.event-interval` | `6s` | Как часто генератор меняет состав; `0s` — только события из кнопок и запросов |

### Профили

Настройки окружений лежат в `backend/assembly/config/application-<профиль>.yml`. Сейчас профиль один — `stage`, он задаёт cookie для подписки на streamapi stage. Без профиля backend работает с prod.

Spring Boot читает эти файлы, только если профиль включён, а процесс запущен из папки `backend/assembly`:

- `bootRun` запускается из этой папки, профиль передаётся аргументом: `--args='--spring.profiles.active=stage'`.
- В IDE запускайте `AttendanceApplication` с рабочей папкой `backend/assembly` и аргументом `--spring.profiles.active=stage`.
- Jar запускайте тоже из `backend/assembly`: `java -jar build/libs/plugin-attendance.jar --spring.profiles.active=stage`.

## API backend

| Запрос | Ответ |
| --- | --- |
| `GET /api/calls/{callId}/participants` | Состав звонка. Первый запрос запускает отслеживание |
| `GET /api/info` | Источник событий и версия |
| `GET /actuator/health` | Проверка доступности |
| `POST /dev/calls/{callId}/events` | Только в демо-режиме. Тело `{"type": "participant.joined", "participantId": "…"}`; без `participantId` войдёт случайный участник или выйдет случайный присутствующий. Типы: `participant.joined`, `participant.left`, `call.ended` |
| `POST /dev/calls/{callId}/connection-loss?seconds=5` | Только в демо-режиме: имитация обрыва связи с источником |

Пример ответа о составе звонка:

```json
{
  "callId": "RM_xxx",
  "source": "streamapi",
  "status": "TRACKING",
  "statusMessage": null,
  "trackingSince": "2026-10-22T11:05:00.123Z",
  "endedAt": null,
  "gaps": [{ "from": "2026-10-22T11:07:03Z", "to": "2026-10-22T11:07:05Z" }],
  "serverTime": "2026-10-22T11:20:00.456Z",
  "present": [{ "participantId": "anna-0123456789abcde", "joinedAt": "2026-10-22T11:06:00Z", "leftAt": null }],
  "left": [{ "participantId": "boris-zzzzzzzzzzzzzzz", "joinedAt": null, "leftAt": "2026-10-22T11:10:00Z" }]
}
```

Возможные статусы:

- `CONNECTING` — подписка ещё не установилась.
- `TRACKING` — события приходят.
- `RECONNECTING` — связь потеряна, плагин переподключается.
- `ENDED` — звонок завершён.
- `FAILED` — streamapi отказал в подписке, повтор через минуту.

`joinedAt: null` означает, что участник вошёл до начала отслеживания. Ошибки приходят в формате `application/problem+json`, текст для пользователя лежит в поле `detail`.

## Встраивание в Meet

Пока нет подписанного контекста запуска, Meet открывает плагин так:

```
<адрес плагина>/?callId=<room.sid>&roomId=<room.name>&participantId=<identity>
```

Во фронтенде Meet эти значения дают `await lkRoom.getSid()`, `lkRoom.name` и `lkRoom.localParticipant.identity`.

- Страница рассчитана на iframe с `sandbox="allow-scripts"`, как у `plugin-iframe-window` в ветке `feat/floating` форка Meet. У такой страницы opaque origin, поэтому плагин не использует cookies и `localStorage`, а backend отдаёт и API, и статику с `Access-Control-Allow-Origin: *`.
- Адрес страницы должен заканчиваться на `/`: пути к ассетам и API относительные, так что плагин работает и под префиксом вроде `/plugins/attendance/`.

## Ограничения версии 0.1

- Учитываются только входы после начала отслеживания, то есть после первого открытия плагина в звонке: streamapi не отдаёт историю и текущий состав. Кто вышел, не войдя на наших глазах, показан с пометкой «вход до начала отслеживания».
- Имён в событиях нет, только identity LiveKit. Имя гостя восстанавливается из латинского префикса identity. Кириллические имена Meet заменяет на `participant`, такие гости показаны как «Участник #метка».
- Состояние живёт в памяти одного процесса: перезапуск обнуляет списки, несколько реплик не поддерживаются.
- Авторизации нет: состав звонка видит любой, кто знает `callId`.
- Время событий LiveKit округлено до секунды. Вход и выход в одну и ту же секунду применяются в порядке прихода.
- События, пришедшие во время обрыва связи со streamapi, теряются, и плагин предупреждает, что список может быть неточным.

## Тесты

Из `backend/`:

```shell
.\gradlew.bat test
```

Тесты покрывают:

- в `core` — логику трекера и реестра звонков, а также gRPC-адаптер против in-process сервера, который ведёт себя как streamapi;
- в `assembly` — приложение целиком в демо-режиме: страницу, CORS для sandbox-iframe и API.

Отдельной команды для фронтенда нет: проверка типов TypeScript входит в его сборку (`npm run build`).

## Структура

```
frontend/
  src/                    React-приложение: опрос API, список участников
  public/                 статика, которая копируется в сборку как есть
  package.json, vite.config.ts, tsconfig.json
backend/
  core/                     код плагина, обычная библиотека
    src/main/java/ru/itmo/plugins/attendance/
      api/                  REST: состав звонка, /api/info, /dev/* для демо-режима
      tracking/             CallTracker — состав одного звонка, CallRegistry — какие звонки отслеживаются
      events/               EventSource и реализации: streamapi (gRPC) и fake
      config/               CORS для sandbox-iframe, проверки настроек
    src/main/proto/streamapi/v1/events.proto   копия контракта streamapi v1.1.0 с java-опциями
  assembly/                 приложение: собирает core и frontend в исполняемый jar
    src/main/java/ru/itmo/plugins/attendance/AttendanceApplication.java   main class
    src/main/resources/application.yaml        настройки по умолчанию
    src/main/resources/static/dev-host.html    страница «плагин в окне Meet» для демо-режима
    config/application-stage.yml               профиль stage: cookie для streamapi stage
  settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew
```

Версии зависимостей лежат в `backend/gradle/libs.versions.toml` (там же версия Node для сборки) и `frontend/package.json`. Версии gRPC и protobuf задаёт BOM Spring Boot.
