# ScholarPulse — Native Android Shell Application

ScholarPulse is a modern academic productivity and study-tracking platform for students. This repository contains the native Android hybrid shell application (built with Kotlin, Jetpack Compose, Android 16/API 36 support, Room SQLite, Credential Manager, and bidirectional Web-to-Native bridge).

---

## 🛠️ Tech Stack & Architecture

- **Language / Platform**: Kotlin 2.0.21, Jetpack Compose, Material 3, Android SDK 26 - 36 (VanillaIceCream / Baklava)
- **Hybrid Bridge**: AndroidX `WebViewCompat` (`WebMessageListener` & `JavaScriptReplyProxy`) with thread-safe atomic interfaces
- **Authentication**: Modern AndroidX Credential Manager (`androidx.credentials`) using Google Identity (`GoogleIdTokenCredential`) + cryptographic token exchange with `/api/auth/google-native`
- **Background Execution**: Native Foreground Service (`TimerForegroundService`) with ongoing status-bar notification for reliable persistent study timers
- **Offline Storage & Sync**: Room Database (`ScholarPulseDatabase`), WorkManager periodic batch synchronization (`OfflineSyncWorker`), and Android Keystore encryption
- **Audio & Haptics**: Low-latency `SoundPool` engine and Android `Vibrator` / `VibrationEffect` engine

---

## 📂 Project Structure

```
.
├── app/                        # Android application module
│   ├── src/main/
│   │   ├── java/com/scholarpulse/app/
│   │   │   ├── alarms/         # Exact alarm schedulers & boot receivers
│   │   │   ├── audio/          # Native audio & haptic feedback engines
│   │   │   ├── auth/           # CredentialManager & cookie sync managers
│   │   │   ├── bridge/         # Bidirectional RPC dispatcher & models
│   │   │   ├── db/             # Room SQLite entities & DAOs
│   │   │   ├── services/       # Timer foreground service & FCM messaging
│   │   │   ├── ui/             # WebAppShell, Compose theme, custom clients
│   │   │   └── workers/        # WorkManager offline sync workers
│   │   └── res/                # XML themes, launcher icons, network security
│   └── build.gradle.kts        # Module configuration & dependencies
├── gradle/                     # Version catalog (libs.versions.toml) & wrapper
├── README.md                   # Application documentation & build instructions
└── settings.gradle.kts         # Gradle project settings
```

---

## 🚀 Building & Running

### Prerequisites
- JDK 17
- Android SDK Platform 35 / 36
- ADB connected device or emulator

### Build Commands
```bash
# Compile debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch application
adb shell am start -n com.scholarpulse.app/.MainActivity
```
