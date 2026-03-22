# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Headunit Revived is an Android application that transforms an Android tablet or phone into an Android Auto head unit receiver. It implements the full Android Auto Protocol (AAP) to communicate with a connected phone via USB or WiFi, handling SSL/TLS handshake, protobuf-based messaging, H.264/H.265 video decoding, multi-channel audio routing, microphone capture, touch/key input, GPS location forwarding, and night mode.

- **Target devices:** Android tablets/phones running Android 4.1+ (minSdk 16) used as car head units
- **Published on:** Google Play Store (`com.andrerinas.headunitrevived`)
- **Forked from:** Original headunit project by Michael Reid (https://github.com/mikereidis/headunit)

## Git Rules

- **NEVER push to `main`** — always work on a feature or fix branch
- **Author:** All commits must be authored by:
  - Name: andrecuellar
  - Email: acuellaravaroma@gmail.com
  - Configure with: `git config user.name "andrecuellar"` and `git config user.email "acuellaravaroma@gmail.com"`
- **No Co-Authors:** Never add `Co-authored-by` trailers to commit messages
- **Branch naming:** Use `feature/`, `fix/` prefixes (e.g. `feature/my-feature`, `fix/my-bug`)
- **PR flow:** Push branch → open PR to upstream (andreknieriem/headunit-revived) → wait for review

## Sensitive & Local Files

The following files must NEVER be committed, pushed, or added to .gitignore.
They should remain completely untracked by Git:

- `secrets.local` — contains keystore passwords
- `CLAUDE.md` — contains project-specific instructions for AI assistants
- `headunit-release-key.jks` — release signing keystore

To verify these files are untracked:
```bash
git status --short | grep -E "secrets.local|CLAUDE.md|headunit-release-key.jks"
# Output should be empty or show "??" (untracked), never "M" or "A"
```

If Git ever stages these files accidentally, unstage them immediately:
```bash
git rm --cached secrets.local CLAUDE.md headunit-release-key.jks 2>/dev/null
```

## Build Instructions

### Prerequisites
- **JDK 17** required (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`). JDK 19+ will fail — it has a runtime but no compiler.
- **Android SDK** with compileSdk 36, NDK 27.0.12077973
- **Gradle 8.13.2** (wrapper included)

### Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (signed)
# Load secrets first
export $(cat secrets.local | xargs)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleRelease

# Install debug on connected device
./gradlew installDebug

# Clean
./gradlew clean

# Lint
./gradlew lint
```

### Release Signing

- **Keystore:** `headunit-release-key.jks` (project root)
- **Key alias:** `headunit-revived`
- **Keystore password:** see `secrets.local`
- **Key password:** see `secrets.local`

Passwords are read from `secrets.properties` file (keys: `HEADUNIT_KEYSTORE_PASSWORD`, `HEADUNIT_KEY_PASSWORD`) or from environment variables with the same names.

To create a new keystore:
```bash
keytool -genkey -v -keystore headunit-release-key.jks -alias headunit-revived -keyalg RSA -keysize 2048 -validity 10000
```

### Low RAM Builds

If the build machine has limited RAM (< 4GB), temporarily set in `gradle.properties`:
```
org.gradle.jvmargs=-Xmx1536m
```
And build with `--no-daemon --no-parallel`. Remember to restore the original value (`-Xmx4096m`) after building.

### Output

Release APK: `app/build/outputs/apk/release/com.andrerinas.headunitrevived_<version>.apk`

### No tests exist in this project.

## Architecture

### Module Structure

- **`/app`** — Main Android application (Kotlin). Contains all AAP protocol logic, UI, decoders, connection management, and native JNI code.
- **`/contract`** — Shared library defining Intent contracts (`HeadUnitIntent.kt`) for inter-component communication. Defines: `ConnectedIntent`, `DisconnectIntent`, `KeyIntent`, `MediaKeyIntent`, `LocationUpdateIntent`, `ProjectionActivityRequest`.

### Dependency Injection

Manual DI via `AppComponent` (instantiated lazily in `App.kt`). Access pattern throughout the codebase:
```kotlin
val settings = App.provide(context).settings
val transport = App.provide(context).transport
```

`AppComponent` holds: `AapTransport` (lazy, resettable), `Settings`, `VideoDecoder`, `AudioDecoder`, system services (`AudioManager`, `NotificationManager`, `WifiManager`), `BackgroundNotification`.

### Connection Flow (Core Path)

1. **Entry:** `HomeFragment` is the start destination. On launch, three auto-connect modes fire in priority order:
   1. Auto-connect last session (WiFi or USB) — uses `lastConnectionType`/`lastConnectionIp`/`lastConnectionUsbDevice` from Settings
   2. Auto-start self mode — launches Android Auto standalone
   3. Auto-connect single USB device — if exactly one USB device is plugged in

2. **USB path:** User selects device in `UsbListFragment` → `UsbAccessoryMode.connectAndSwitch()` sends USB AOA control transfers (manufacturer="Android", model="Android Auto", version="2.0.1") → phone re-enumerates in accessory mode → `UsbReceiver` detects re-enumeration → `AapService.onStartCommand()` creates `UsbAccessoryConnection` (16KB internal read buffer)

3. **WiFi path:** Manual IP entry or `NetworkDiscovery` scan → two-phase scan: quick gateway check, then full /24 subnet scan → ports 5277 (Headunit Server) and 5289 (WiFi Launcher) → `AapService.onStartCommand()` creates `SocketAccessoryConnection` (65KB buffered streams, TCP no-delay, network-bound socket on Android M+)

4. **Post-connection:** `AapTransport.start()` → SSL handshake (up to 3 retries, 5s timeout) → version exchange → service discovery response (advertises video/audio/input/sensor capabilities) → channel setup → broadcasts `ConnectedIntent` → `AapProjectionActivity` launches

5. **Projection:** `AapProjectionActivity` displays decoded video via one of three rendering backends, captures touch events as protobuf `TouchEvent` messages, and forwards key events. Video is decoded by `VideoDecoder` (MediaCodec with Surface output). Audio goes through `AudioDecoder` with up to 3 separate `AudioTrackWrapper` channels.

6. **Disconnect:** `DisconnectIntent` broadcast → `AapService.onDisconnect()` → `AapTransport.stop()` sends ByeByeRequest → closes connection, stops decoders → `AapProjectionActivity` finishes

### Key Packages (`com.andrerinas.headunitrevived`)

| Package | Purpose |
|---------|---------|
| `aap/` | AAP protocol core: `AapService` (foreground service, connection lifecycle, wireless server, NSD), `AapTransport` (SSL + message framing, send/poll HandlerThreads), `AapProjectionActivity` (video display + touch input), `AapControl` (control channel handler), `AapAudio`/`AapVideo` (media channels) |
| `aap/protocol/` | `Channel.kt` (14 channel IDs: CTR, SEN, VID, INP, AU1, AU2, AUD, MIC, BTH, MPB, NAV, NOT, PHONE, WIFI), `MsgType.kt` (message type names), `AudioConfigs.kt` (per-channel audio params) |
| `aap/protocol/messages/` | Protobuf message builders: `TouchEvent`, `KeyCodeEvent`, `SensorEvent`, `VideoFocusEvent`, `NightModeEvent`, `ServiceDiscoveryResponse`, `MediaAck`, etc. |
| `connection/` | `UsbAccessoryConnection` (16KB buffered USB reads), `SocketAccessoryConnection` (TCP with network binding), `UsbAccessoryMode` (AOA protocol for mode switching), `NetworkDiscovery` (two-phase WiFi scan), `UsbReceiver` (USB attach/detach/permission broadcasts), `UsbDeviceCompat` |
| `decoder/` | `VideoDecoder` (H.264/H.265 via MediaCodec, SPS parsing, hw/sw selection), `AudioDecoder` (multi-channel AudioTrack), `MicRecorder` (voice input) |
| `view/` | Three rendering backends implementing `IProjectionView`: `ProjectionView` (SurfaceView), `TextureProjectionView` (TextureView), `GlProjectionView` (OpenGL ES 2.0 with custom shaders). Plus `ProjectionViewScaler` for aspect ratio. |
| `ssl/` | `ConscryptInitializer` (TLS 1.2 on Android < 5 via Conscrypt 2.5.3), `SslContextFactory`, `AapSslContext` (Java SSL), `AapSslNative` (JNI OpenSSL) |
| `main/` | UI layer: `MainActivity` (NavHost, deep link handler for `headunit://connect?ip=...`), `HomeFragment` (main screen with USB/WiFi/Self Mode buttons + auto-connect logic), `UsbListFragment`, `NetworkListFragment`, `SettingsFragment` (pending changes pattern), `KeymapFragment`, `AboutFragment` |
| `utils/` | `Settings` (SharedPreferences wrapper, 40+ properties), `AppLog` (debug-mode-aware logging), `LogExporter` (FileProvider-based log sharing), `NightModeManager`, `LocaleHelper`, `SystemUI`, `HeadUnitScreenConfig` |
| `app/` | `UsbAttachedActivity` (transparent, handles USB_DEVICE_ATTACHED intent), `BootCompleteReceiver`, `AutoStartReceiver` (Bluetooth ACL_CONNECTED → auto-launch), `RemoteControlReceiver` (media buttons) |
| `location/` | `GpsLocationService`, `GpsLocation` — forwards device GPS to phone via AAP sensor channel |

### Settings System

`utils/Settings.kt` wraps `SharedPreferences` with Kotlin property accessors. Key categories:

- **Connection:** `allowedDevices` (Set), `networkAddresses` (Set), `autoConnectLastSession`, `autoConnectSingleUsbDevice`, `lastConnectionType`/`Ip`/`UsbDevice`, `wifiConnectionMode` (0=Manual, 1=Auto/Headunit Server, 2=Helper/WiFi Launcher)
- **Display:** `resolutionId` (Auto/480p/720p/1080p/1440p), `dpiPixelDensity`, `viewMode` (SURFACE/TEXTURE/GLES), `screenOrientation`, `startInFullscreenMode`, `insetLeft/Top/Right/Bottom`
- **Video:** `videoCodec` (Auto/H.264/H.265), `fpsLimit` (30/60), `forceSoftwareDecoding`
- **Audio:** `enableAudioSink`, `useAacAudio`, `micSampleRate`, `micInputSource`, `mediaVolumeOffset`, `assistantVolumeOffset`, `navigationVolumeOffset`
- **Night Mode:** `nightMode` (6 modes: Auto/Day/Night/Manual Time/Light Sensor/Screen Brightness), threshold and time range settings
- **Bluetooth:** `autoStartBluetoothDeviceName`/`Mac`
- **Advanced:** `useNativeSsl`, `debugMode`, `rightHandDrive`, `autoStartSelfMode`, `appLanguage`, `keyCodes` (remapping map)

**Pending changes pattern:** `SettingsFragment` stores all edits in local `pending*` variables. Changes only persist when the user explicitly taps "Save". Some settings (resolution, codec, DPI, SSL mode, insets) require an app restart — the save button changes to "Save and Restart" in that case.

### Navigation

Two separate navigation contexts:
- **`nav_graph.xml`:** `HomeFragment` (start) → `UsbListFragment` / `NetworkListFragment` (slide transitions)
- **`SettingsActivity`** (separate activity): `SettingsFragment` → `KeymapFragment` / `AboutFragment`

Deep link: `headunit://connect?ip=X.X.X.X` triggers WiFi connection from `MainActivity.handleIntent()`.

### Native Code (JNI)

`/app/src/main/jni/`: `hu_jni.c`, `hu_ssl.c`, `hu_uti.c` — OpenSSL-based SSL operations exposed via `AapSslNative.kt`. Built via CMake (`/app/CMakeLists.txt`) with pre-built OpenSSL 1.1.x shared libraries per ABI. ARM64 and x86_64 use 16KB page alignment (`-Wl,-z,max-page-size=16384`) for Google Play compliance.

### Protocol Buffers

`/app/src/main/proto/` — 7 proto files defining the AAP wire format:
- `common.proto` — MessageStatus enum (42 codes), HeadUnitInfo
- `control.proto` — Service discovery, channel open/close, version, SSL, ping, audio/nav focus, bye-bye. Defines video resolutions (480p–4K), codecs, DriverPosition
- `input.proto` — Touch/key/relative/absolute input events
- `media.proto` — Audio/video codec config, stream types (SPEECH/SYSTEM/MEDIA/ALARM/GUIDANCE), media data, ACK, mic request
- `navigation.proto` — Turn-by-turn details and distances
- `sensors.proto` — 21 sensor types (GPS, compass, speed, RPM, fuel, parking brake, gear, night, driving status, etc.)
- `playback.proto` — Media metadata (song, artist, album art), playback status

### Localization

11 locales (10 translations + English default) in `app/src/main/res/values-XX/`:
`cs`, `de`, `es`, `es-rES`, `nl`, `pl`, `pt-rBR`, `ru`, `uk`, `zh-rTW`

Available locales are **auto-detected at build time** by scanning `values-*/strings.xml` directories and stored in `BuildConfig.AVAILABLE_LOCALES`. To add a new language, just create the `values-XX/strings.xml` file — no other config needed.

## Project Structure

```
/app
  /src/main
    /java/com/andrerinas/headunitrevived/   # Kotlin source (packages listed above)
    /jni/                                   # C source (hu_jni.c, hu_ssl.c, hu_uti.c)
    /jni/headers/                           # OpenSSL headers
    /jniLibs/{abi}/                         # Pre-built OpenSSL .so libraries
    /proto/                                 # Protocol Buffer definitions
    /res/
      /values/strings.xml                   # English strings (229 entries)
      /values-{locale}/strings.xml          # Translations
      /values-night/                        # Dark theme overrides
      /navigation/nav_graph.xml             # Navigation graph
      /xml/usb_device_filter.xml            # USB device matching (all devices + Google AOA)
      /xml/provider_paths.xml               # FileProvider for log export
  CMakeLists.txt                            # Native build config
  proguard-project.txt                      # ProGuard rules (protobuf, conscrypt, navigation, JNI)
  multidex-config.pro                       # Primary dex keep rules
/contract                                   # Shared Intent definitions library
/headunit-release-key.jks                   # Release signing keystore
```

## Development Guidelines

### Adding a New Setting

1. Add the property to `utils/Settings.kt` with a SharedPreferences key and default value
2. Add a `pending*` variable in `SettingsFragment.kt`
3. Add the UI item in `SettingsFragment.updateSettingsList()` under the appropriate category
4. Apply the pending value in `SettingsFragment.saveSettings()`
5. Add comparison in `SettingsFragment.checkChanges()`
6. If the setting requires restart, add it to the restart check in `checkChanges()`
7. Add string resources to `values/strings.xml` (label + description)
8. Add translations to all 10 locale `strings.xml` files

### Adding a New Translation

Just create `app/src/main/res/values-XX/strings.xml` with translated strings. The build system auto-detects it and adds it to `BuildConfig.AVAILABLE_LOCALES`. The language selector in settings will pick it up automatically.

### Coding Style

- Kotlin throughout (no new Java). JVM target 1.8 for wide device compatibility.
- Manual dependency injection via `AppComponent` — no Dagger/Hilt.
- Coroutines for async work in services; `HandlerThread` for real-time transport send/poll loops.
- MVVM with AndroidX ViewModels and LiveData in the UI layer.
- `AppLog` for logging — respects `debugMode` setting. Tag convention: class name.

## Pull Request Workflow

1. Always sync fork before starting new work:
   ```bash
   git fetch upstream && git merge upstream/main
   ```
2. Create a new branch from updated main: `git checkout -b feature/my-feature`
3. Make changes, commit with descriptive messages (no Co-authored-by)
4. Push branch: `git push origin feature/my-feature`
5. Open PR on GitHub to: `andreknieriem/headunit-revived`
6. In the PR comment, mention if the PR depends on another PR being merged first
7. Note: Each PR in a chain needs to be approved before the next one is relevant

## Testing on Device

Target test device: 2013 Honda Civic LX with a Chinese Android head unit (CMZX65-U1)
- Android Auto via USB using HeadUnit Revived app
- Wireless Android Auto via USB-to-WiFi converter + HeadUnit Revived

To install APK on device:
```bash
# Via ADB
adb install app/build/outputs/apk/release/com.andrerinas.headunitrevived_<version>.apk

# Or transfer APK manually and install from file manager
```

Release APK output path:
`app/build/outputs/apk/release/com.andrerinas.headunitrevived_<version>.apk`

## Known Active Branches & Features

| Branch | Status | Description |
|--------|--------|-------------|
| `feature/auto-connect-single-usb-device` | Pushed | Auto-connects when exactly one USB device is plugged in. Adds setting toggle, HomeFragment logic (priority 3), translations for all locales. |
| `feature/dynamic-language-selector` | Merged (#120) | In-app language selector in settings with auto-detected locales from BuildConfig |
| `feature/spanish-spain-locale` | Merged (#122) | Added `es-rES` locale and fixed hardcoded strings |
| `fix/hardcoded-strings` | Pushed | Fixes hardcoded English strings that weren't in string resources |
| `fix/usb-auto-connect-permission-v2` | PR #185 | USB permission request when auto-connect lacks permission, resolveUsbDevice fallback for Xtrons, background connectAndSwitch. Standalone re-submission (no CommManager). |
| `fix/stale-accessory-reconnect-v2` | Pushed (waiting for #185) | Stale wireless AA dongle reconnection via AOA re-enumeration after handshake failure. USB auto-reconnect scheduling on disconnect. |

## Known Issues & Quirks

- **MediaTek headunits:** `SocketAccessoryConnection` has a workaround that catches and suppresses exceptions containing "CtaHttp" during socket connect — a known MediaTek crash in their HTTP inspection layer.
- **USB 16KB buffer:** `UsbAccessoryConnection` uses a 16KB internal read buffer (matching HUR 6.3 behavior) to prevent data loss. Changing this buffer size can cause USB instability.
- **SYSTEM_ALERT_WINDOW permission:** Declared twice in the manifest (known, harmless).
- **Self Mode:** Uses a crafted intent targeting `com.google.android.projection.gearhead` with fake network/WiFi info to trigger Android Auto's wireless setup flow in loopback mode. This is fragile and depends on AA internals.
- **SSL retry:** The handshake retries up to 3 times with 5s timeout per attempt. Native SSL (`useNativeSsl`) can be toggled in debug settings as fallback if Conscrypt SSL fails on certain devices.
- **minSdk 16 vs 21:** The actual minSdk is 16 (Android 4.1), but Google Play Console requires 21. Code has conditional paths for pre-Lollipop devices throughout (Conscrypt init, network binding reflection, system UI compat).
- **ProGuard:** `PackagedPrivateKey` lint warning is explicitly suppressed. The `proguard-project.txt` keeps protobuf classes, navigation fragments, JNI classes, and Conscrypt.
- **Wireless server:** `AapService` can run a wireless server on port 5288 with NSD service type `_aawireless._tcp` for incoming connections from phone.
- **Video decoder:** SPS parsing for H.264 extracts actual video dimensions. H.265 falls back to negotiated resolution. Max input buffer is capped at 1MB for memory efficiency.
- **Audio channels:** Three separate audio tracks (AUD=48kHz stereo, AU1=16kHz mono, AU2=16kHz mono) with independent volume offsets for media, assistant, and navigation.

## Debugging

```bash
# View application logs
adb logcat | grep "HeadUnit"

# Protocol-level dump
# Enable debugMode in Settings → Debug section, then AapDump logs hex dumps

# Export logs from device
# Settings → Debug → Export Logs (uses FileProvider to share)
```
