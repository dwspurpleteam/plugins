# Локальная проверка активации через Java SDK

Backend использует `pluginsdk:plugin-sdk:1.1.0`. SDK передаёт activationId и Bearer-секрет, управляет подпиской и переподключением при UNAVAILABLE (до восьми попыток).

Из `plugin-attendance/backend`:

```powershell
.\gradlew.bat build
java -jar build/libs/plugin-attendance.jar --spring.profiles.active=local
```

SDK автоматически скачивается из GitHub Packages. Сборка не зависит от локальной копии исходников SDK. Для скачивания один раз задайте `gpr.user` и `gpr.key` в пользовательском `~/.gradle/gradle.properties`; подробности — в [README](../README.md). В CI используются credentials GitHub Actions с доступом к пакету.

Внешний профиль `config/application-local.yml` запускает backend и UI на http://127.0.0.1:18081, выбирает production StreamAPI `calls.vareonix.cc:443` с TLS. Для IDE выполните Reload Gradle Project и задайте Spring-профиль local. Альтернативный запуск:

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

Плагин сам открывает исходящее соединение со StreamAPI; для чтения событий ему не нужен туннель. Но для доставки команды от серверного Plugin Manager потребуется отдельная настройка доступного ему HTTPS-адреса. Она пока не выполнялась.

Теперь запуск выполняется через **POST /control**, с activationId и activationSecret, выданными Plugin Manager. Полное тело, curl, статусы и повторы описаны в [контракте активации](activation.md).

Коллекция Bruno находится в `../bruno/`: Open Collection, выбрать Local, заполнить callId, activationId и activationSecret. Окружение Production используется только после отдельного развёртывания новой сборки. Настоящие секреты в репозиторий не сохраняйте.

Для просмотра откройте `http://127.0.0.1:18081/?callId=RM_REAL_CALL_ID`. UI не запускает активацию. До POST /control API состава возвращает 404. Начальный состав пока не принимается в учёт; отслеживаются только новые события после подключения.


Профиль stage предназначен для серверной Docker-сети и выбирает nginx-stage:19090. Для локальной работы с этим окружением измените внешние параметры подключения под его публичный доступ; внутренний серверный профиль на локальной машине не используется.
