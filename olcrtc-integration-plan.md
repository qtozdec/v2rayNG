# Интеграция olcRTC в v2rayNG

## Обзор

**olcRTC** — Go-проект, туннелирующий TCP-трафик через WebRTC DataChannel поверх Яндекс.Телемост. Клиентская часть поднимает SOCKS5-прокси, весь трафик идёт через зашифрованный (XChaCha20-Poly1305) канал в Telemost-комнату, где серверная часть выполняет TCP-dial.

**v2rayNG** — Android-приложение (Kotlin), использующее xray-core (через `libv2ray.aar`, gomobile-биндинг). VPN-сервис перехватывает трафик через TUN-интерфейс, направляя его в SOCKS-inbound xray-core, который маршрутизирует через outbound-протоколы (vmess, vless, trojan, и т.д.).

**Цель**: olcRTC-клиент как самостоятельный транспорт в v2rayNG — пользователь вводит Room ID + ключ, приложение туннелирует весь трафик через Telemost.

---

## Архитектура

### Текущий v2rayNG

```
[Android Apps]
       ↓
[TUN Interface / VpnService]
       ↓
[tun2socks (hev-socks5-tunnel)] ← optional
       ↓
[xray-core (libv2ray.aar)]
  ├─ SOCKS inbound (:10808)
  ├─ HTTP inbound (:10809)
  └─ Outbound (vmess/vless/trojan/ss/...)
       ↓
[Remote Server]
```

### С olcRTC (реализованный вариант)

```
[Android Apps]
       ↓
[TUN Interface / VpnService]
       ↓
[hev-socks5-tunnel → SOCKS5 (:10808)]
       ↓
[olcRTC-client SOCKS5 (:10808)]
       ↓
[WebRTC DataChannel → Telemost Room]
       ↓
[olcRTC-server → TCP dial → Internet]
```

olcRTC-клиент предоставляет SOCKS5-прокси напрямую. xray-core **не используется** для olcRTC-профилей — olcRTC полностью заменяет его. hev-socks5-tunnel принудительно включается для направления TUN-трафика в SOCKS5 olcRTC.

---

## Что было сделано

### Фаза 1: Go-сторона — подготовка olcRTC для Android

#### 1.1 Embed данных имён (убрана зависимость от файловой системы)

**Файл:** `olcrtc/internal/names/names.go`

**Проблема:** olcRTC при запуске загружал файлы `data/names` и `data/surnames` через `os.Open()`. На Android у gomobile-библиотеки нет доступа к этим файлам — путь к ним неизвестен, assets не распакованы.

**Решение:** Добавлен `//go:embed` для встраивания файлов имён прямо в бинарник на этапе компиляции.

```go
//go:embed data/names
var embeddedNames string

//go:embed data/surnames
var embeddedSurnames string
```

Добавлена функция `init()`, которая парсит embed-данные при загрузке пакета. Если embed-файлы пусты — используются захардкоженные `defaultFirstNames`/`defaultLastNames` (30 имён/фамилий). Функция `LoadNameFiles()` сохранена для обратной совместимости с CLI-режимом.

Файлы `data/names` (735 имён) и `data/surnames` (735 фамилий) скопированы в `internal/names/data/` для работы `//go:embed`.

#### 1.2 Protected Dialer — защита сокетов от VPN-петли

**Файл:** `olcrtc/internal/protect/protect.go` (новый пакет)

**Проблема:** Когда v2rayNG включает Android VPN, весь сетевой трафик устройства перенаправляется через TUN-интерфейс. Если olcRTC создаёт HTTP/WebSocket/WebRTC-соединения к Telemost — они тоже попадут в VPN → направятся обратно в olcRTC → бесконечная петля. 

На Android это решается вызовом `VpnService.protect(socketFd)` для каждого сокета, который должен идти напрямую в интернет, минуя VPN.

**Решение:** Создан пакет `internal/protect` с глобальной переменной `Protector func(fd int) bool`. Когда установлен protector, все сокеты перед `connect()` вызывают `syscall.RawConn.Control()` для получения file descriptor и передачи его в `Protector`.

Пакет предоставляет:

| Функция | Назначение |
|---------|-----------|
| `Protector` | Глобальный callback, вызывается с fd перед connect. На Android это `VpnService.protect(fd)` |
| `NewDialer()` | `net.Dialer` с `Control` функцией, защищающей TCP-сокеты |
| `NewHTTPClient()` | `http.Client` с транспортом на базе protected dialer — для HTTP API |
| `DialContext()` | Shortcut для `NewDialer().DialContext()` |
| `NewProxyDialer()` | Реализация `golang.org/x/net/proxy.Dialer` для pion ICE |

#### 1.3 Применение Protected Dialer к Telemost

**Файл:** `olcrtc/internal/telemost/api.go`

Заменён `http.DefaultClient` на `protect.NewHTTPClient()` в функции `GetConnectionInfo()`. Теперь HTTP-запрос к `cloud-api.yandex.ru` для получения credentials комнаты идёт через protected-сокет.

**Файл:** `olcrtc/internal/telemost/peer.go`

Три изменения:

1. **WebSocket:** `websocket.DefaultDialer` заменён на `websocket.Dialer` с `NetDialContext: protect.DialContext`. WebSocket-соединение к медиасерверу Telemost теперь защищено от VPN.

2. **WebRTC ICE:** Добавлен `settingEngine.SetICEProxyDialer(protect.NewProxyDialer())`. pion/webrtc использует `proxy.Dialer` интерфейс (`golang.org/x/net/proxy`) для создания ICE-соединений (STUN к `stun.rtc.yandex.net:3478`, TURN relay). `NewProxyDialer()` оборачивает protected dialer в этот интерфейс.

3. Protector устанавливается только когда `protect.Protector != nil` — на десктопе/CLI protect не нужен, код работает как раньше.

#### 1.4 Gomobile API обёртка

**Файл:** `olcrtc/mobile/mobile.go` (новый пакет)

Gomobile может экспортировать только функции с примитивными типами и интерфейсами. Создан пакет `mobile` с минимальным API:

```go
// Интерфейсы для Kotlin/Java
type SocketProtector interface {
    Protect(fd int) bool
}
type LogWriter interface {
    WriteLog(msg string)
}

// Управление
func SetProtector(p SocketProtector)  // устанавливает VPN protect callback
func SetLogWriter(w LogWriter)        // перенаправляет логи в Android Log
func SetDebug(enabled bool)           // включает verbose-логирование
func Start(roomID, keyHex string, socksPort int, duo bool) error  // запуск
func Stop()                           // остановка
func IsRunning() bool                 // статус
```

**Детали реализации:**

- `Start()` запускает `client.Run()` в отдельной goroutine и сразу возвращает управление. Это критически важно — gomobile вызовы происходят из UI-потока Android.
- `Stop()` вызывает `context.Cancel()` и ждёт завершения goroutine через канал `done`.
- Потокобезопасность: `sync.Mutex` защищает `cancel`/`done` от race condition при параллельных Start/Stop.
- `SetProtector()` транслирует Java/Kotlin интерфейс `SocketProtector` в Go `func(fd int) bool` через замыкание.
- `SetLogWriter()` перенаправляет `log.SetOutput()` на bridge, вызывающий Kotlin callback — логи olcRTC появляются в Android logcat.

---

### Фаза 2: Kotlin-сторона — интеграция в v2rayNG

#### 2.1 Новый тип конфигурации

**Файл:** `V2rayNG/app/src/main/java/com/v2ray/ang/AppConfig.kt`

Добавлена константа протокола:
```kotlin
const val OLCRTC = "olcrtc://"
```

**Файл:** `V2rayNG/app/src/main/java/com/v2ray/ang/enums/EConfigType.kt`

Добавлен enum-элемент:
```kotlin
OLCRTC(11, AppConfig.OLCRTC),
```

`value = 11` — следующий свободный номер после HTTP(10). Это значение сохраняется в MMKV-хранилище для идентификации типа профиля.

#### 2.2 Маппинг полей ProfileItem

`ProfileItem` уже содержит достаточно полей для olcRTC — новые поля не добавлялись:

| Поле ProfileItem | Использование в olcRTC |
|-----------------|----------------------|
| `server` | Room ID Telemost (например `abc-def-ghi`) |
| `password` | Hex-ключ шифрования (64 символа = 32 байта) |
| `serverPort` | Не используется, установлен в `"0"` |
| `headerType` | `"duo"` для двухканального режима, пустая строка иначе |
| `remarks` | Имя профиля (задаётся пользователем) |
| `configType` | `EConfigType.OLCRTC` |

#### 2.3 Форматтер OlcrtcFmt

**Файл:** `V2rayNG/app/src/main/java/com/v2ray/ang/fmt/OlcrtcFmt.kt` (новый)

Формат URI: `olcrtc://ROOM_ID?key=HEX_KEY&duo=true#remarks`

- `parse(str)` — разбирает URI, извлекает Room ID из host, key и duo из query-параметров, remarks из fragment.
- `toUri(config)` — собирает URI обратно для экспорта/шаринга профиля.

**Файл:** `V2rayNG/app/src/main/java/com/v2ray/ang/handler/AngConfigManager.kt`

Зарегистрирован OlcrtcFmt в двух местах:
- `shareConfig()` — для экспорта профиля как URI-строки
- `parseConfig()` — для импорта из `olcrtc://` ссылки (по аналогии с `vless://`, `trojan://`)

#### 2.4 OlcrtcManager — управление жизненным циклом

**Файл:** `V2rayNG/app/src/main/java/com/v2ray/ang/handler/OlcrtcManager.kt` (новый)

Kotlin-обёртка вокруг gomobile bindings пакета `mobile`:

```kotlin
object OlcrtcManager {
    fun start(config: ProfileItem, socksPort: Int, protectSocket: ((Int) -> Boolean)?): Boolean
    fun stop()
    fun isRunning(): Boolean
}
```

**`start()`** делает три вещи:
1. Устанавливает `SocketProtector` — callback для `VpnService.protect(fd)`, чтобы сокеты Telemost шли мимо VPN.
2. Устанавливает `LogWriter` — bridge для перенаправления Go-логов в `android.util.Log.d()`.
3. Вызывает `Mobile.start(roomID, keyHex, socksPort, duo)` — запускает SOCKS5-прокси olcRTC.

#### 2.5 UI: Экран настройки сервера

**Файл:** `V2rayNG/app/src/main/res/layout/activity_server_olcrtc.xml` (новый)

Минимальный layout с полями:
- **Remarks** (`et_remarks`) — имя профиля
- **Telemost Room ID** (`et_address`) — ID комнаты
- **Encryption Key** (`et_id`) — hex-ключ, monospace-шрифт
- **Duo mode** (`cb_duo`) — CheckBox для двухканального режима
- Скрытое поле `et_port` (значение `"0"`, не показывается пользователю)

**Файл:** `V2rayNG/app/src/main/java/com/v2ray/ang/ui/ServerActivity.kt`

Модификации:
- Добавлен case `EConfigType.OLCRTC -> R.layout.activity_server_olcrtc` для выбора layout.
- В `bindingServer()` — загрузка чекбокса duo из `config.headerType`.
- В `saveCommon()` — сохранение `serverPort = "0"` и duo из CheckBox.
- В `saveServer()` — пропуск `saveStreamSettings()`/`saveTls()` (у olcRTC нет TLS/transport настроек).
- Пропуск валидации порта для OLCRTC (порт не вводится пользователем).

**Файл:** `V2rayNG/app/src/main/res/values/strings.xml`

Добавлены строковые ресурсы:
```xml
<string name="menu_item_import_config_manually_olcrtc">olcRTC</string>
<string name="olcrtc_lab_room_id">Telemost Room ID</string>
<string name="olcrtc_lab_key">Encryption Key (hex)</string>
<string name="olcrtc_lab_duo">Duo mode (2x channels)</string>
```

#### 2.6 Пункт меню

**Файл:** `V2rayNG/app/src/main/res/menu/menu_main.xml`

Добавлен пункт `import_manually_olcrtc` в подменю ручного добавления сервера (после Hysteria2).

**Файл:** `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt`

Добавлен обработчик `R.id.import_manually_olcrtc` — вызывает `importManually(EConfigType.OLCRTC.value)`, открывая `ServerActivity` с layout olcRTC.

#### 2.7 Интеграция в сервисную инфраструктуру

**Файл:** `V2rayNG/app/src/main/java/com/v2ray/ang/handler/V2RayServiceManager.kt`

Ключевые изменения:

1. **`startCoreLoop()`** — добавлено ветвление: если `config.configType == EConfigType.OLCRTC`, вызывается `startOlcrtcLoop()` вместо генерации V2ray JSON-конфига и запуска xray-core. Это принципиально — olcRTC не использует xray-core вообще.

2. **`startOlcrtcLoop()`** (новый private метод) — получает SOCKS-порт из настроек, создаёт `protectSocket` callback из `ServiceControl.vpnProtect()`, вызывает `OlcrtcManager.start()`, управляет notification и UI-сообщениями.

3. **`stopCoreLoop()`** — добавлена остановка olcRTC (`OlcrtcManager.stop()`) перед остановкой xray-core. Обе остановки выполняются в `Dispatchers.IO`.

4. **`isRunning()`** — теперь `coreController.isRunning || OlcrtcManager.isRunning()`. Это важно для корректного отображения статуса в UI.

5. **`startContextService()`** — olcRTC исключён из валидации server-адреса (`isValidUrl`/`isPureIpAddress`), поскольку `server` содержит Room ID, а не IP/URL.

**Файл:** `V2rayNG/app/src/main/java/com/v2ray/ang/service/V2RayVpnService.kt`

Изменение в `runTun2socks()`: для olcRTC **принудительно** запускается hev-socks5-tunnel, даже если пользователь не включил настройку `isUsingHevTun`.

**Почему:** В обычном режиме v2rayNG может работать без hev-tunnel — xray-core сам читает TUN fd через `coreController.startLoop(config, tunFd)`. Но olcRTC не читает TUN — он предоставляет SOCKS5. Поэтому нужен промежуточный компонент (hev-socks5-tunnel), который читает TUN-пакеты и проксирует их в SOCKS5 olcRTC.

Добавлен helper `isOlcrtcMode()` для определения текущего типа профиля.

### Фаза 3: Сборка

**Файл:** `build-olcrtc.sh` (новый)

Скрипт для сборки `olcrtc.aar` через gomobile:

```bash
gomobile bind \
  -target="android/arm64,android/arm" \
  -androidapi 24 \
  -ldflags="-s -w -checklinkname=0" \
  -o V2rayNG/app/libs/olcrtc.aar \
  ./mobile
```

Флаги:
- `-s -w` — strip debug info, уменьшение размера .aar
- `-checklinkname=0` — требуется для Go 1.25+ на Android (обход проверки linkname)
- `-androidapi 24` — минимальный API level, совпадает с `minSdk` проекта

---

## Полный список изменённых и созданных файлов

### Go (olcrtc/)

| Файл | Статус | Описание |
|------|--------|----------|
| `mobile/mobile.go` | **Новый** | Gomobile API: Start/Stop/SetProtector/SetLogWriter |
| `internal/protect/protect.go` | **Новый** | VPN socket protection: Protector, NewDialer, NewHTTPClient, NewProxyDialer |
| `internal/names/data/names` | **Новый** | Копия файла имён для go:embed |
| `internal/names/data/surnames` | **Новый** | Копия файла фамилий для go:embed |
| `internal/names/names.go` | Изменён | Добавлен go:embed, init() для автозагрузки |
| `internal/telemost/api.go` | Изменён | http.DefaultClient → protect.NewHTTPClient() |
| `internal/telemost/peer.go` | Изменён | WebSocket/ICE через protected dialer |

### Kotlin (V2rayNG/)

| Файл | Статус | Описание |
|------|--------|----------|
| `handler/OlcrtcManager.kt` | **Новый** | Lifecycle olcRTC: start/stop/isRunning |
| `fmt/OlcrtcFmt.kt` | **Новый** | Парсинг/генерация olcrtc:// URI |
| `res/layout/activity_server_olcrtc.xml` | **Новый** | UI: Room ID, Key, Duo checkbox |
| `AppConfig.kt` | Изменён | +OLCRTC protocol scheme |
| `enums/EConfigType.kt` | Изменён | +OLCRTC(11) enum |
| `handler/AngConfigManager.kt` | Изменён | +OlcrtcFmt в import/share |
| `handler/V2RayServiceManager.kt` | Изменён | +startOlcrtcLoop(), olcRTC stop, isRunning |
| `service/V2RayVpnService.kt` | Изменён | Принудительный hev-tunnel для olcRTC |
| `ui/ServerActivity.kt` | Изменён | Layout/save/load для olcRTC profiles |
| `ui/MainActivity.kt` | Изменён | Пункт меню "olcRTC" |
| `res/menu/menu_main.xml` | Изменён | +import_manually_olcrtc |
| `res/values/strings.xml` | Изменён | +строки olcRTC |

### Сборка

| Файл | Статус | Описание |
|------|--------|----------|
| `build-olcrtc.sh` | **Новый** | gomobile bind → olcrtc.aar |

---

## Как запустить

### Предварительные требования

```bash
# Go 1.25+
go version

# gomobile
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init

# Android SDK + NDK (через Android Studio или sdkmanager)
export ANDROID_HOME=/path/to/android/sdk
```

### Сборка olcrtc.aar

```bash
./build-olcrtc.sh
```

Результат: `V2rayNG/app/libs/olcrtc.aar`

### Сборка APK

Открыть `V2rayNG/` в Android Studio, собрать как обычно. Gradle подхватит `olcrtc.aar` из `app/libs/`.

### Использование

1. Запустить olcRTC-сервер на удалённой машине:
   ```bash
   ./olcrtc -mode srv -id ROOM_ID -key SHARED_KEY_HEX
   ```

2. В приложении: **+** → **olcRTC** → ввести Room ID и Key → сохранить → подключиться.

---

## Поток данных при подключении

```
1. Пользователь нажимает "Connect" с olcRTC-профилем
2. V2RayServiceManager.startContextService()
   → определяет VPN/Proxy режим → запускает V2RayVpnService или V2RayProxyOnlyService
3. VpnService.setupVpnService()
   → создаёт TUN интерфейс
   → runTun2socks() → isOlcrtcMode()=true → принудительно запускает hev-socks5-tunnel
4. V2RayServiceManager.startCoreLoop()
   → config.configType == OLCRTC → startOlcrtcLoop()
   → OlcrtcManager.start(config, socksPort=10808, protectSocket=VpnService::protect)
5. OlcrtcManager:
   → Mobile.setProtector(VpnService.protect wrapper)
   → Mobile.start(roomID, keyHex, 10808, duo)
6. Go mobile.Start():
   → client.Run(ctx, roomURL, keyHex, 10808, duo)
   → telemost.GetConnectionInfo() [protected HTTP]
   → telemost.NewPeer() → Connect() [protected WebSocket + ICE]
   → DataChannel opened
   → SOCKS5 listener на :10808
7. Трафик:
   App → TUN → hev-socks5-tunnel → SOCKS5(:10808) → olcRTC mux → encrypt → DataChannel
   → Telemost SFU → DataChannel → decrypt → demux → server TCP dial → Internet
```

---

## Риски и нерешённые вопросы

1. **ICE UDP protect:** `SetICEProxyDialer()` защищает TCP-соединения ICE. UDP STUN-пакеты могут создаваться внутри pion без прохождения через proxy dialer. Для полной защиты может потребоваться `SettingEngine.SetNet()` с кастомной реализацией `transport.Net`, что значительно сложнее. Тестирование покажет, достаточно ли текущего подхода.

2. **Telemost API стабильность:** API Телемоста не документирован, endpoints и формат сообщений могут меняться без предупреждения.

3. **Go 1.25.0:** olcRTC требует Go 1.25+. Нужно убедиться, что текущая версия gomobile поддерживает эту версию Go.

4. **Размер APK:** pion/webrtc + зависимости добавят ~5-10 МБ к `.aar`.

5. **Энергопотребление:** WebRTC keep-alive (ping каждые 5с) и ICE connectivity checks расходуют батарею больше, чем обычный TCP/WS транспорт.

6. **Proxy-only режим:** В proxy-only (не VPN) режиме `vpnProtect` является noop — это корректно, так как VPN не активен и петли нет. Но пользователь должен вручную настроить приложения на SOCKS5 proxy.
