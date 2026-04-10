# Сборка объединённого libv2ray.aar (xray-core + olcRTC) на Windows (PowerShell)

## Предварительные требования

### 1. Go 1.26+

```powershell
go version
# go version go1.26.1 windows/amd64
```

### 2. Android Studio (SDK + NDK)

- File → Settings → Languages & Frameworks → Android SDK
- Вкладка **SDK Tools**
- Поставить галку **NDK (Side by side)** — скачает последнюю версию
- Поставить галку **Android SDK Command-line Tools**
- Apply

### 3. Переменные окружения (PowerShell)

```powershell
$env:ANDROID_HOME = "C:\Users\qtozdec\AppData\Local\Android\Sdk"
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME\ndk\30.0.14904198"
$env:PATH += ";C:\Program Files\Android\Android Studio\jbr\bin"
```

### 4. gomobile

```powershell
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
$env:PATH += ";$env:USERPROFILE\go\bin"
gomobile init
```

---

## Подготовка

```powershell
cd E:\v2rayng

# Инициализировать оба субмодуля (AndroidLibXrayLite + hev-socks5-tunnel)
git submodule update --init --recursive

# Создать go.work (не закоммичен в репо — локальный файл)
go work init .\AndroidLibXrayLite .\olcrtc
go work sync
```

### Windows: заменить git-symlinks в hev-socks5-tunnel

На Windows без админ-прав git не восстанавливает symlinks, а создаёт текстовые
файлы-заглушки. Сборка `ndk-build` на них падает. Выполнить один раз после
клонирования:

```powershell
python scripts\fix-hev-symlinks.py
```

(или вручную пройти по `hev-socks5-tunnel\src`, `hev-task-system`, `third-part\lwip`,
`third-part\yaml` и заменить каждый symlink-stub реальной копией целевого файла).

### Сборка libhev-socks5-tunnel.so (один раз, NDK 28)

```powershell
cd E:\v2rayng\hev-socks5-tunnel
& "$env:ANDROID_HOME\ndk\28.2.13676358\ndk-build.cmd" `
  APP_CFLAGS="-O3 -DPKGNAME=com/v2ray/ang/service -DCLSNAME=TProxyService"
```

Скопировать получившиеся `libs\<abi>\libhev-socks5-tunnel.so` в
`V2rayNG\app\libs\<abi>\`. NDK 30+ не собирается (конфликт `errno.h` в lwip),
нужен именно NDK 28.

---

## Сборка

```powershell
cd E:\v2rayng

# Собрать объединённый .aar (xray-core + olcRTC в одном libgojni.so)
gomobile bind -target="android/arm64,android/arm" -androidapi 24 -ldflags="-s -w -checklinkname=0" -o V2rayNG\app\libs\libv2ray.aar github.com/2dust/AndroidLibXrayLite github.com/openlibrecommunity/olcrtc/mobile
```

Результат: `V2rayNG\app\libs\libv2ray.aar` (~32 МБ)

---

## Сборка APK

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd E:\v2rayng\V2rayNG
.\gradlew.bat assembleFdroidDebug
```

APK будет в `V2rayNG\app\build\outputs\apk\fdroid\debug\`

---

## Почему объединённый .aar?

gomobile всегда называет нативную библиотеку `libgojni.so`. Если собрать два отдельных `.aar` (libv2ray + olcrtc), в APK может быть только один `libgojni.so` — второй теряет все нативные символы → `UnsatisfiedLinkError`.

Решение — собрать один `.aar` одним вызовом `gomobile bind` с обоими пакетами. Один Go runtime, один `libgojni.so`, все символы на месте.

---

## Если что-то не работает

| Ошибка | Решение |
|--------|---------|
| `gomobile: command not found` | `$env:PATH += ";$env:USERPROFILE\go\bin"` |
| `ANDROID_HOME is not set` | `$env:ANDROID_HOME = "C:\Users\qtozdec\AppData\Local\Android\Sdk"` |
| `no Android NDK found` | Установить NDK через Android Studio SDK Manager |
| `javac: not found` | `$env:PATH += ";C:\Program Files\Android\Android Studio\jbr\bin"` |
| `go.work not found` | Создать: `go work init ./AndroidLibXrayLite ./olcrtc` (не коммитится) |
| `AndroidLibXrayLite empty` | `git submodule update --init AndroidLibXrayLite` |
| `MissingArgument` на запятой | Обернуть target в кавычки: `-target="android/arm64,android/arm"` |
| `hev-socks5-tunnel: No such file` на `hev-task-system/include/...` | Symlinks не восстановились — см. раздел «Windows: заменить git-symlinks» |
| `errno.h` errors при ndk-build | Используется NDK 30+, нужен NDK 28 (см. выше) |
| `UnsatisfiedLinkError: No implementation found for TProxyStartService` | libhev-socks5-tunnel.so собрана без `-DPKGNAME=com/v2ray/ang/service` |
