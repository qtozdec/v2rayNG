# Сборка APK и запуск olcRTC-сервера

Этот гайд описывает рабочий путь для текущего репозитория `v2rayNG + olcRTC`.

Коротко:

- Android-проект лежит в `V2rayNG/`.
- Готовый `libv2ray.aar` должен лежать в `V2rayNG/app/libs/libv2ray.aar`.
- Готовые `libhev-socks5-tunnel.so` должны лежать в `V2rayNG/app/libs/<abi>/`.
- APK собирается Gradle wrapper'ом из `V2rayNG/`.
- olcRTC-сервер удобнее запускать через Docker/Podman Compose из `olcrtc/`.

## 1. Требования

### Windows / PowerShell для APK

Понадобятся:

- Android Studio с JDK 17 из комплекта Android Studio.
- Android SDK Platform 36.
- Android SDK Build-Tools.
- Android SDK Command-line Tools.
- Android NDK 28.2.13676358 для пересборки `hev-socks5-tunnel`.
- Go 1.25+ для Docker-сборки сервера и Go 1.26+ для локальной `gomobile`-сборки, если используется текущий upstream `olcRTC`.
- `gomobile` и `gobind`, если нужно пересобрать `libv2ray.aar`.

Пример переменных окружения для текущей машины:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME\ndk\28.2.13676358"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH += ";$env:JAVA_HOME\bin;$env:USERPROFILE\go\bin"
```

Проверка:

```powershell
java -version
go version
```

## 2. Быстрая сборка APK

Если `V2rayNG/app/libs/libv2ray.aar` и `V2rayNG/app/libs/<abi>/libhev-socks5-tunnel.so` уже есть, пересобирать нативные части не нужно.

```powershell
cd E:\v2rayng\V2rayNG
.\gradlew.bat assembleFdroidDebug
```

Результат:

```text
V2rayNG\app\build\outputs\apk\fdroid\debug\
```

По умолчанию собираются ABI-split APK и universal APK. Имена формируются в `V2rayNG/app/build.gradle.kts`, например:

```text
v2rayNG_2.0.18-fdroid_arm64-v8a.apk
v2rayNG_2.0.18-fdroid_universal.apk
```

Для Play Store flavor:

```powershell
cd E:\v2rayng\V2rayNG
.\gradlew.bat assemblePlaystoreDebug
```

Для release-сборки без подписи:

```powershell
cd E:\v2rayng\V2rayNG
.\gradlew.bat assembleFdroidRelease
```

## 3. Подготовка нативных зависимостей

Этот раздел нужен только если пересобирается `libv2ray.aar` или `libhev-socks5-tunnel.so`.

```powershell
cd E:\v2rayng
git submodule update --init --recursive
```

На Windows без прав на symlink'и Git может восстановить symlink'и `hev-socks5-tunnel` как текстовые файлы-заглушки. После клонирования или обновления submodule'ов выполните:

```powershell
cd E:\v2rayng
python scripts\fix-hev-symlinks.py
```

## 4. Пересборка libhev-socks5-tunnel.so

Используйте NDK 28. NDK 30+ может падать на конфликте `errno.h` в `lwip`.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd E:\v2rayng\hev-socks5-tunnel
& "$env:ANDROID_HOME\ndk\28.2.13676358\ndk-build.cmd" `
  APP_CFLAGS="-O3 -DPKGNAME=com/v2ray/ang/service -DCLSNAME=TProxyService"
```

После сборки скопируйте результат:

```powershell
cd E:\v2rayng
Copy-Item hev-socks5-tunnel\libs\arm64-v8a\libhev-socks5-tunnel.so V2rayNG\app\libs\arm64-v8a\ -Force
Copy-Item hev-socks5-tunnel\libs\armeabi-v7a\libhev-socks5-tunnel.so V2rayNG\app\libs\armeabi-v7a\ -Force
Copy-Item hev-socks5-tunnel\libs\x86\libhev-socks5-tunnel.so V2rayNG\app\libs\x86\ -Force
Copy-Item hev-socks5-tunnel\libs\x86_64\libhev-socks5-tunnel.so V2rayNG\app\libs\x86_64\ -Force
```

Важно: флаги `PKGNAME` и `CLSNAME` должны совпадать с JNI-классом Android-приложения, иначе будет `UnsatisfiedLinkError` для `TProxyStartService`.

## 5. Пересборка libv2ray.aar

Установка `gomobile`:

```powershell
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init
```

Рекомендуемый путь на Windows - запускать готовый скрипт через Git Bash или WSL:

```bash
cd /e/v2rayng
./build-olcrtc.sh
```

Скрипт пересоздаёт локальный `go.work`, синхронизирует зависимости и собирает единый:

```text
V2rayNG/app/libs/libv2ray.aar
```

Эквивалентная команда вручную из PowerShell:

```powershell
cd E:\v2rayng
Remove-Item go.work, go.work.sum -ErrorAction SilentlyContinue
go work init .\AndroidLibXrayLite .\olcrtc
go work sync

gomobile bind `
  -target="android/arm64,android/arm" `
  -androidapi 24 `
  -ldflags="-s -w -checklinkname=0" `
  -o V2rayNG\app\libs\libv2ray.aar `
  github.com/2dust/AndroidLibXrayLite `
  github.com/openlibrecommunity/olcrtc/mobile
```

Почему один общий `.aar`: `gomobile` всегда кладёт нативный Go runtime в `libgojni.so`. Если собрать `AndroidLibXrayLite` и `olcRTC` в два разных `.aar`, в APK останется только один `libgojni.so`, и часть JNI-символов потеряется.

После пересборки `.aar` снова выполните сборку APK из раздела 2.

## 6. Запуск olcRTC-сервера

Сервер не слушает входящий TCP-порт. Он сам держит исходящие соединения к Telemost и проксирует трафик через комнату. Клиенту нужны одинаковые `Room ID` и 64-символьный hex-ключ.

### Вариант A: Docker Compose / Podman Compose

```bash
cd olcrtc
export OLCRTC_ROOM_ID="abc-def-ghi"
export OLCRTC_KEY="$(openssl rand -hex 32)"
docker compose -f docker-compose.server.yml up -d --build
```

Для Podman:

```bash
cd olcrtc
export OLCRTC_ROOM_ID="abc-def-ghi"
export OLCRTC_KEY="$(openssl rand -hex 32)"
podman compose -f docker-compose.server.yml up -d --build
```

Логи:

```bash
docker logs -f olcrtc-server
```

или:

```bash
podman logs -f olcrtc-server
```

Остановка:

```bash
docker compose -f docker-compose.server.yml down
```

или:

```bash
podman compose -f docker-compose.server.yml down
```

Если `OLCRTC_KEY` не задан, entrypoint сгенерирует ключ, сохранит его в `/var/lib/olcrtc/key.hex` и один раз напечатает в лог:

```bash
docker logs olcrtc-server
```

### Вариант B: быстрый Podman-скрипт

Скрипт клонирует upstream `olcRTC`, собирает бинарник в контейнере Go и запускает сервер:

```bash
cd olcrtc
./srv.sh
```

Он попросит Telemost Room ID и сохранит ключ в `~/.olcrtc_key`.

### Вариант C: нативный запуск

```bash
cd olcrtc
go build -trimpath -ldflags="-s -w" -o build/olcrtc ./cmd/olcrtc
./build/olcrtc -mode srv -id "abc-def-ghi" -key "64-hex-character-shared-key" -dns "1.1.1.1:53"
```

Для подробных логов:

```bash
./build/olcrtc -mode srv -id "abc-def-ghi" -key "64-hex-character-shared-key" -debug
```

Для двух параллельных WebRTC-каналов:

```bash
./build/olcrtc -mode srv -id "abc-def-ghi" -key "64-hex-character-shared-key" -duo
```

## 7. Настройка клиента в приложении

В Android-приложении добавьте профиль `olcRTC`:

- `Room ID`: тот же ID комнаты Telemost, например `abc-def-ghi`.
- `Key`: тот же 64-символьный hex-ключ.
- `Duo`: включайте только если сервер запущен с `-duo` или `OLCRTC_DUO=true`.

Порт сервера для `olcRTC` не используется: в профиле он хранится как `0`.

## 8. Частые ошибки

| Ошибка | Что сделать |
| --- | --- |
| `gomobile: command not found` | Добавить `$env:USERPROFILE\go\bin` в `PATH`. |
| `ANDROID_HOME is not set` | Задать `$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"`. |
| `no Android NDK found` | Установить NDK через Android Studio SDK Manager. |
| `javac: not found` | Задать `$env:JAVA_HOME` на JBR из Android Studio. |
| `go.work not found` | Создать заново: `go work init .\AndroidLibXrayLite .\olcrtc && go work sync`. |
| `AndroidLibXrayLite empty` | Выполнить `git submodule update --init --recursive`. |
| `MissingArgument` на запятой в PowerShell | Обернуть target в кавычки: `-target="android/arm64,android/arm"`. |
| `hev-socks5-tunnel: No such file` на путях symlink'ов | Выполнить `python scripts\fix-hev-symlinks.py`. |
| `errno.h` errors при `ndk-build` | Использовать NDK 28.2.13676358. |
| `UnsatisfiedLinkError: TProxyStartService` | Пересобрать `libhev-socks5-tunnel.so` с `-DPKGNAME=com/v2ray/ang/service -DCLSNAME=TProxyService`. |
| `UnsatisfiedLinkError` для Go/JNI-символов | Пересобрать один общий `libv2ray.aar` с обоими Go-пакетами в одном `gomobile bind`. |
| `OLCRTC_KEY must be 64 hex characters` | Использовать ключ `openssl rand -hex 32`. |
| Сервер стартует, но клиент не соединяется | Проверить совпадение `Room ID`, `Key` и режима `Duo` на сервере и клиенте. |
