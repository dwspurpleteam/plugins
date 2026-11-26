# Активация attendance

`POST /control` принимает сообщение Plugin Manager (`schemaVersion: "1.0"`). Старый путь `/platform/control` не поддерживается. Адрес StreamAPI и TLS берутся из серверной конфигурации `attendance.streamapi.*`.

```bash
curl --noproxy '*' --request POST 'http://127.0.0.1:18081/control' \
  --header 'Content-Type: application/json' \
  --data-raw '{
    "schemaVersion":"1.0",
    "activationId":"UUID_FROM_PLUGIN_MANAGER",
    "activationSecret":"SECRET_FROM_PLUGIN_MANAGER",
    "pluginId":"attendance",
    "pluginVersion":"1.0.1",
    "registryId":"local",
    "callId":"RM_REAL_CALL_ID",
    "initialContext":null
  }'
```

ID и секрет должны быть выданы тем Plugin Manager, который проверяет разрешения для StreamAPI. Произвольный UUID с произвольным секретом сервер не авторизует. Менеджер доставляет секрет только backend плагина; публичные API состояния его не раскрывают. Не используйте глобальный ключ Plugin Manager в качестве activationSecret.

## Запуск

Backend хранит обработку по activationId, привязывает её к callId и создаёт отдельный SDK-клиент через `.activation(id, secret)`. Подписка включает `participant.joined`, `participant.left` и `call.ended`; эти события должны быть разрешены манифестом. Для одного звонка допускается одна одновременно работающая активация attendance; другой ID для уже занятого звонка возвращает 409. Разные звонки имеют независимые трекеры, клиенты и подписки.

HTTP 200 возвращается после SDK `onConnected()` с телом:

```json
{"activationId":"...","callId":"RM_...","status":"TRACKING"}
```

`TRACKING` — локальное состояние подписки. Plugin Manager переводит свою запись в `ACTIVE` после успешного ответа. Создание подписки и ожидание заголовков gRPC используют общий бюджет `attendance.activation.start-timeout`. По умолчанию это 4 секунды, в профиле `local` — 2,5 секунды с запасом на CloudPub. Если за это время подписка не готова, плагин возвращает 504 `ACTIVATION_TIMEOUT` и закрывает подписку и SDK-клиент. Плагин пишет в лог итог и длительность обработки `/control` с `activationId`.

Отказ StreamAPI возвращает 502 `ACTIVATION_FAILED` с причиной. Несовместимое сообщение — 400. Повтор с изменёнными параметрами того же ID — 409. Одновременные одинаковые запросы ждут одну подписку. Повтор успешного запуска не создаёт ресурсов заново; после неудачного запуска тот же запрос может начать новую попытку. Состояние и дедупликация хранятся в памяти процесса, завершённые записи очищаются; долговременной дедупликации между перезапусками нет.

Манифест с `initial-state: ["participants"]` запрашивает начальный состав. Плагин добавляет участников из `initialContext.participants` в `present` при первом подключении к StreamAPI и учитывает их с `trackingSince`. Если контекст отсутствует или список пуст, состав заполняется по событиям StreamAPI.

## Диагностика

Профиль `local` включает DEBUG для attendance и пишет логи в консоль и `backend/logs/attendance.log` при запуске из каталога `backend`. Файл ротируется при достижении 10 МБ, архивы хранятся за 7 дней с общим лимитом 100 МБ. Путь можно изменить через `logging.file.name` в `config/application-local.yml`. Изменения конфигурации применяются после перезапуска приложения.

Для других профилей DEBUG можно включить аргументом `--logging.level.ru.itmo.plugins.attendance=DEBUG` в **Program arguments** IntelliJ или при запуске из PowerShell.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

Строки `HTTP #… started` и `HTTP #… completed` показывают начало и конец запроса, его путь, статус и длительность. По ним видно, дошли ли до backend `GET /actuator/health` и `POST /control`. По `activationId` можно сопоставить обработку команды с запуском подписки StreamAPI и логами Plugin Manager. Тела HTTP-запросов, заголовки и параметры URL в эти логи не попадают.

## Чтение состояния

```bash
curl --noproxy '*' 'http://127.0.0.1:18081/api/calls/RM_REAL_CALL_ID/participants'
```


Активация не зависит от открытой страницы и не удаляется по UI idle-timeout. `call.ended`, окончательная ошибка SDK, завершение процесса закрывают подписку и клиент. Завершённый/ошибочный снимок удаляется по failed-retention. Протокол управляющих STOP-сообщений Plugin Manager пока не определён; HTTP-метода ручной остановки нет.

Локальная страница: `http://127.0.0.1:18081/?callId=RM_REAL_CALL_ID`. Она только читает готовую обработку.

Манифест должен содержать control-url с путём `/control`. Настройка внешнего HTTPS-доступа для серверного менеджера в эту доработку не входит.
