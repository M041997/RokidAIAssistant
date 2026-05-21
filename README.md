# Rokid AI Assistant

> 📖 [繁體中文版](doc/zh-TW/README.md)

**AI-powered voice and vision assistant for Rokid AR glasses.**


[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/liangtinglin)

---

## Current Project Checkpoint

Last verified checkpoint: `live-translation-frame-debug` at `2026-05-21 09:23 CDT`

Current working state:

- Current branch: `live-translation-frame-debug`.
- Latest pushed remote checkpoint on `m041997/live-translation-frame-debug` includes photo and live translation readable-surface ROI/object crop fixes.
- Rokid AI on the glasses is back to normal.
- Separate glasses-menu app `ROKID BLUETOOTH BLUE LIGHT ENABLED` is installed and confirmed working.
- Selecting `ROKID BLUETOOTH BLUE LIGHT ENABLED` opens Android discoverable mode, shows `Finding glasses...`, turns on the glasses blue light after allowing the prompt, and lets the Pixel/RokidApkUploader find the glasses.
- Rokid APK uploader helper scripts can stage the main glasses app or the separate Bluetooth launcher app without manually browsing for APKs.
- Phone app auto-connects to the Pixel 7 path used during testing.
- Photo translation works best in light mode / readable screen conditions.
- Photo translation now keeps the whole wearer view on the glasses, then the phone rotates the frame, detects the bright readable screen/object region, crops/enhances/upscales it, and saves `latest_photo_translation_analyzed.jpg` for debugging.
- Photo/live translation model inputs now include a full detected readable surface plus an enlarged center crop of the same surface so normal sitting-distance text has both context and a zoomed reading view.
- Photo translation prompt now asks the model to identify the main readable surface first and translate partially readable text instead of falling back too quickly to `No translatable text visible`.
- Photo translation pagination/auto-advance feels good in testing.
- Live visual translation works for Japanese and Spanish to English when the frame is readable.
- Live visual translation now allows a new API call after the reading timer ends, even if the view is still stable/similar.
- Live visual translation now keeps the whole wearer view on the glasses, then the phone finds the largest connected bright readable surface, crops/enhances/upscales it, and prompts the model to identify the screen/sign/page before translating.
- Edge TTS audio is routed to the glasses over Bluetooth and played by the glasses app instead of playing through the Pixel speaker.
- Local backup zip was created and copied to the Expansion drive.

What we are testing next:

1. Re-run the blue-light launcher flow once from a cold start: glasses menu -> `ROKID BLUETOOTH BLUE LIGHT ENABLED` -> allow prompt -> confirm blue light -> uploader finds glasses.
2. Upload the newly staged glasses APK from the Pixel uploader.
3. Re-check live translation from normal sitting distance with light-mode screen text and confirm it no longer falls back too quickly to `No translatable text visible`.
4. Re-check that live translation makes another API call after the reading timer ends.
5. Re-check live translation to English with Japanese and Spanish, since that path was already good before the ROI/object crop change.
6. Decide whether to keep tuning photo/live crop/upscale or move on to spatial text overlay research.

Notes:

- The phone APK is already installed for the current checkpoint.
- The glasses Rokid AI APK is installed and back to normal.
- The separate Bluetooth launcher APK is installed as its own glasses menu option.
- `/sdcard/Download/...` means the Pixel's internal Downloads folder, not a physical SD card.
- The current experience is live translation text on the glasses, not yet a Google Translate-style spatial text replacement overlay.
- Local Qwen test URL: `http://100.114.53.77:11440/v1` with model `qwen3` / `qwen3GGUF_moe`; Pixel must be connected to Tailscale.
- Backup zip on Expansion:
  `/media/boss/Expansion/RokidAIAssistant_Backups/RokidAIAssistant_backup_20260521-085059_live-translation-frame-debug.zip`
- Backup SHA-256:
  `a5ef79532c31543ec6db1b6cd5b60d765b43a83324bf48e5451a7e4f0d04add1`

Spatial translation overlay status:

- It should be possible to line translated text up with the source text location in the image or glasses overlay, but the current app does not support that path yet.
- Current flow: glasses send camera frames to the phone, the phone asks the vision model for only the English translation, then sends plain `AI_RESPONSE_TEXT` back to the glasses.
- Current glasses UI renders that text as one centered Compose text block, not as positioned text over the camera/glasses view.
- To support spatial overlays, the vision/OCR step would need structured results such as source text, translated text, and bounding boxes. The phone would need to send those boxes to the glasses, and the glasses app would need to draw translated text at normalized overlay coordinates.
- Coordinate mapping will need care because live visual translation frames are rotated, center-cropped, zoomed `2x`, resized, and compressed before the phone analyzes them.

---

## 🚀 Quick Start (5 minutes)

```bash
# 1. Clone
git clone https://github.com/your-repo/RokidAIAssistant.git && cd RokidAIAssistant

# 2. Configure API keys
cp local.properties.template local.properties
# Edit local.properties → Add your GEMINI_API_KEY (required)
# .env is also supported for GEMINI_API_KEY, GoogleGeminiAPIKey,
# GOOGLE_GEMINI_API_KEY, or GOOGLE_API_KEY.

# 3. Build & Install
./gradlew :phone-app:installDebug    # Install phone app
./gradlew :glasses-app:installDebug  # Install glasses app (on Rokid device)
```

> **Minimum requirement**: Only `GEMINI_API_KEY` is needed to run. Get one at [Google AI Studio](https://ai.google.dev/).

---

## Scope

### In Scope

- Voice-to-text transcription and AI chat on Rokid AR glasses
- Photo capture from glasses camera with AI image analysis
- Live visual translation from glasses camera frames to English
- Phone ↔ Glasses communication via Rokid CXR SDK
- Multiple AI/STT provider support (Gemini, OpenAI, Anthropic, etc.)
- Conversation history persistence

### Out of Scope

- Standalone glasses-only operation (phone required for AI processing)
- Offline AI inference
- Google Translate-style spatial text replacement overlays

---

## Features

| Feature                 | Description                                                                                                                                                                                                                                |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 🎤 Voice Interaction    | Speak to AI through glasses or phone                                                                                                                                                                                                       |
| 📷 Photo Analysis       | Capture images with glasses camera, get AI analysis                                                                                                                                                                                        |
| 🌐 Live Translation     | Continuously translate visible text from glasses camera frames into English, with auto-detect and configurable source language                                                                                                              |
| 🎙️ Recording & Analysis | Record audio from phone or glasses with auto AI transcription and analysis                                                                                                                                                                 |
| 🤖 Multi-AI Providers   | 14 providers: Gemini, OpenAI, Anthropic, DeepSeek, Groq, xAI, Alibaba (Qwen), Zhipu (GLM), Baidu, Perplexity, Moonshot (Kimi), Mistral, Gemini Live, Custom (OpenAI-compatible)                                                            |
| 🎧 Multi-STT Providers  | 18 providers: Gemini, OpenAI Whisper, Groq Whisper, Deepgram, AssemblyAI, Azure Speech, iFLYTEK, Google Cloud STT, AWS Transcribe, Alibaba ASR, Tencent ASR, Baidu ASR, IBM Watson, Huawei SIS, Volcengine, Rev.ai, Speechmatics, Otter.ai |
| 📱 Phone-Glasses Comm   | Via Rokid CXR SDK and Bluetooth SPP                                                                                                                                                                                                        |
| 💬 Conversation History | Room database persistence                                                                                                                                                                                                                  |
| 🌍 Multi-Language       | 13 languages: English, 简体中文, 繁體中文, 日本語, 한국어, Español, Français, Italiano, Русский, Українська, العربية, Tiếng Việt, ไทย                                                                                                      |

---

## Module / Directory Guide

```
RokidAIAssistant/
├── phone-app/                    # 📱 Phone app (main AI hub)
│   └── src/main/java/.../rokidphone/
│       ├── MainActivity.kt       # Entry point
│       ├── service/ai/           # AI provider implementations
│       ├── service/stt/          # STT provider implementations
│       ├── service/cxr/          # CXR SDK manager
│       ├── data/db/              # Room database
│       ├── ui/                   # Compose UI screens
│       └── viewmodel/            # ViewModels
│
├── glasses-app/                  # 👓 Glasses app (display/input)
│   └── src/main/java/.../rokidglasses/
│       ├── MainActivity.kt       # Entry point
│       ├── service/photo/        # Camera service
│       ├── ui/                   # Compose UI
│       └── viewmodel/            # GlassesViewModel
│
├── common/                       # 📦 Shared protocol library
│   └── src/main/java/.../rokidcommon/
│       ├── Constants.kt          # Shared constants
│       └── protocol/             # Message, MessageType, ConnectionState
│
├── app/                          # 🧪 Original integrated app (dev only)
├── doc/                          # 📚 Documentation
└── gradle/libs.versions.toml     # Version catalog
```

| Module        | App ID                     | Purpose                               |
| ------------- | -------------------------- | ------------------------------------- |
| `phone-app`   | `com.example.rokidphone`   | AI processing, STT, CXR SDK, database |
| `glasses-app` | `com.example.rokidglasses` | Display, camera, wake word            |
| `common`      | (library)                  | Shared protocol & constants           |

---

## Technology Stack

| Category    | Technology                   | Version              |
| ----------- | ---------------------------- | -------------------- |
| Language    | Kotlin                       | 2.2.10               |
| Min SDK     | Android                      | 28 (9.0 Pie)         |
| Target SDK  | Android                      | 34 (14)              |
| Compile SDK | Android                      | 36                   |
| Build       | Gradle + Kotlin DSL          | AGP 9.0 / Gradle 9.3 |
| UI          | Jetpack Compose + Material 3 | BOM 2026.01.00       |
| Async       | Kotlin Coroutines            | 1.10.2               |
| Database    | Room                         | 2.8.4                |
| Networking  | Retrofit + OkHttp            | 3.0 / 5.3            |
| Rokid SDK   | CXR client-m                 | 1.0.4                |

---

## Build & Run

### Prerequisites

- **Android Studio**: Ladybug (2024.2) or later
- **JDK**: 21 (recommended for AGP 9 and CI)
- **Android SDK**: API 36 installed

### Environment Setup

```bash
# Copy template and edit with your keys
cp local.properties.template local.properties
```

### CI: Inject a Single `sn_auth_file.*` Resource

The app enforces a single-source SN auth strategy in `app/src/main/res/raw/`.
Keep exactly one file named `sn_auth_file.*` (for example: `sn_auth_file.lc`).

```yaml
- name: Prepare SN auth resource (single source)
   shell: bash
   run: |
      mkdir -p app/src/main/res/raw
      rm -f app/src/main/res/raw/sn_auth_file.*
      echo "${{ secrets.SN_AUTH_FILE_BASE64 }}" | base64 --decode > app/src/main/res/raw/sn_auth_file.lc

- name: Build app module
   run: ./gradlew :app:assembleDebug --no-daemon
```

Notes:

- Store the SN file as base64 in `SN_AUTH_FILE_BASE64` (GitHub Secret).
- Do not commit `sn_auth_file.*` into version control.
- Build will fail fast if multiple `sn_auth_file.*` files exist.

**Required keys in `local.properties`:**

```properties
# Required
GEMINI_API_KEY=your_gemini_api_key

# Required for glasses connection
ROKID_CLIENT_SECRET=your_rokid_secret_without_hyphens

# Optional
OPENAI_API_KEY=your_openai_key
ANTHROPIC_API_KEY=your_anthropic_key
```

### Gradle Commands

```bash
# Build all modules (debug)
./gradlew assembleDebug

# Build specific module
./gradlew :phone-app:assembleDebug
./gradlew :glasses-app:assembleDebug

# Install to connected device
./gradlew :phone-app:installDebug
./gradlew :glasses-app:installDebug

# Build release APK
./gradlew assembleRelease

# Clean build
./gradlew clean
```

### APK Output Locations

```
phone-app/build/outputs/apk/debug/phone-app-debug.apk
phone-app/build/outputs/apk/release/phone-app-release.apk
glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
glasses-app/build/outputs/apk/release/glasses-app-release.apk
```

---

## Debug vs Release

| Aspect       | Debug            | Release                       |
| ------------ | ---------------- | ----------------------------- |
| Minification | ❌ Disabled      | ✅ Enabled (ProGuard)         |
| Debuggable   | ✅ Yes           | ❌ No                         |
| Signing      | Debug keystore   | Release keystore (required)   |
| BuildConfig  | API keys visible | API keys visible (obfuscated) |
| Performance  | Slower           | Optimized                     |

### ProGuard Rules

- `phone-app/proguard-rules.pro` - Keeps Gemini, OkHttp, Gson, common protocol
- `glasses-app/proguard-rules.pro` - Keeps CXR SDK, common protocol

---

## Testing

Unit and integration test suites are implemented for protocol, service, factory, and data-layer paths.

### Run Tests

```bash
# Cross-module unit tests
./gradlew :common:testDebugUnitTest :phone-app:testDebugUnitTest :glasses-app:testDebugUnitTest

# Targeted suites
./gradlew :common:testDebugUnitTest --tests "com.example.rokidcommon.protocol.*"
./gradlew :phone-app:testDebugUnitTest --tests "com.example.rokidphone.service.ai.*"
./gradlew :phone-app:testDebugUnitTest --tests "com.example.rokidphone.service.stt.*"

# Phone instrumented tests (Room/data-layer)
./gradlew :phone-app:connectedDebugAndroidTest
```

### Manual Testing Checklist

Current checkpoint:

- [x] Phone app reads the build-time `.env` Gemini key
- [x] In-app status bar confirms Gemini key validity
- [x] Glasses audio recording processes through STT and AI response
- [x] Live Japanese to English visual translation works
- [x] Auto-detect visual translation mode is implemented
- [x] Live visual translation start/stop is a single toggle control
- [x] Glasses APK with `2x` live-frame zoom is built and staged on the phone
- [x] Re-upload staged glasses APK with RokidApkUploader
- [x] Custom/Qwen OpenAI-compatible vision provider connects over Tailscale
- [x] Custom/Qwen live visual translation path calls the local server instead of Gemini
- [x] Live visual translation has stable-frame gating and a `20s` reading timer
- [x] Rokid AI glasses app restored to normal after Bluetooth menu experiments
- [x] Separate glasses menu app `ROKID BLUETOOTH BLUE LIGHT ENABLED` installed
- [x] Blue-light launcher turns on the glasses blue light and lets the Pixel uploader find the glasses
- [x] Project zip backup copied to `/media/boss/Expansion/RokidAIAssistant_Backups/`
- [x] Photo translation auto-page advance feels good
- [x] Live translation reading timer no longer refreshes forever on the same stable view before rechecking
- [x] Edge TTS playback is routed to glasses instead of Pixel speaker
- [ ] Validate live-frame orientation and readability under normal wearing conditions
- [ ] Test auto-detect translation on Japanese, Spanish, German, and French across Gemini and Qwen
- [ ] Decide whether to tune zoom/crop/rotation after seeing new captured frames

1. **Phone App**
   - [ ] Launch app, verify Settings screen loads
   - [ ] Configure AI provider (Gemini), test text chat
   - [ ] Test voice input from phone microphone
   - [ ] Verify conversation history persists after restart

2. **Glasses App**
   - [ ] Install on Rokid glasses, verify UI displays
   - [ ] Test camera photo capture
   - [ ] Verify photo transfer to phone

3. **Integration**
   - [ ] Pair phone with glasses via CXR SDK
   - [ ] Test voice command from glasses → AI response displayed
   - [ ] Test photo capture → AI analysis → result displayed
   - [ ] Test live visual translation → English result displayed on glasses

### Running Instrumentation Tests

```bash
./gradlew :phone-app:connectedAndroidTest
./gradlew :glasses-app:connectedAndroidTest
```

---

## Common Developer Tasks

### Add a New AI Provider

1. Create implementation in `phone-app/src/.../service/ai/YourProvider.kt`
2. Implement `AiServiceProvider` interface (see [ARCHITECTURE.md](doc/ARCHITECTURE.md#ai-service-provider-interface))
3. Register in `AiServiceFactory.kt`
4. Add to `AiProvider` enum in settings

### Add a New Screen (Compose)

1. Create screen composable in `phone-app/src/.../ui/yourscreen/YourScreen.kt`
2. Create ViewModel in `phone-app/src/.../viewmodel/YourViewModel.kt`
3. Add route to `phone-app/src/.../ui/navigation/AppNavigation.kt`

### Add a New Permission

1. Add to `AndroidManifest.xml`:
   ```xml
   <uses-permission android:name="android.permission.YOUR_PERMISSION" />
   ```
2. Request at runtime (for dangerous permissions) in Activity/ViewModel

---

## FAQ & Troubleshooting

### Build Issues

**Q: Build fails with "API key not found"**

```
A: Ensure local.properties or .env exists in the project root.
   Supported Gemini key names include GEMINI_API_KEY, GoogleGeminiAPIKey,
   GOOGLE_GEMINI_API_KEY, and GOOGLE_API_KEY.
```

**Q: Gradle sync fails with version errors**

```
A: Ensure Android Studio has SDK 36 installed.
   File → Settings → SDK Manager → Install API 36.
```

**Q: JDK version mismatch**

```
A: Project requires JDK 21 (matches AGP 9 and CI).
   File → Settings → Build → Gradle → Gradle JDK → Select JDK 21.
```

### Runtime Issues

**Q: App crashes on launch**

```
A: Check Logcat for missing API key errors.
   Ensure all required permissions are granted.
```

**Q: Cannot connect to glasses**

```
A: 1. Verify ROKID_CLIENT_SECRET is set (without hyphens)
   2. Enable Bluetooth on both devices
   3. Ensure glasses are in pairing mode
```

**Q: AI responses are empty**

```
A: 1. Verify API key is valid and has quota
   2. Check the in-app pipeline status bar
   3. Check network connectivity
   4. Review Logcat for API error responses
```

**Q: RokidApkUploader asks for an APK in /sdcard/Download, but there is no SD card**

```
A: /sdcard/Download is Android's legacy name for shared internal storage.
   Use the Pixel's normal Downloads folder.
```

**Q: How do I update the glasses APK without hunting for the file every time?**

```
A: Run scripts/update_glasses_apk.sh.
   It builds the glasses debug APK, pushes it to:
   /sdcard/Download/glasses-app-debug.apk
   Then it launches RokidApkUploader, selects the APK, and fills
   the serial number from debug_frames/rokid_serial.txt.

   If the APK is already built and you only need to refill the
   uploader form, run scripts/fill_rokid_uploader.sh.

   To stage the separate glasses-menu Bluetooth launcher APK, run:
   scripts/fill_bluetooth_launcher_apk.sh.
```

### Release Issues

**Q: Release build fails with signing error**

```
A: Create a release keystore and configure in build.gradle.kts:
   signingConfigs {
       create("release") {
           storeFile = file("path/to/keystore.jks")
           storePassword = "password"
           keyAlias = "alias"
           keyPassword = "password"
       }
   }
```

**Q: ProGuard removes required classes**

```
A: Add keep rules to proguard-rules.pro:
   -keep class com.your.package.** { *; }
```

---

## Documentation

| Document                                                      | Description                                  |
| ------------------------------------------------------------- | -------------------------------------------- |
| [API Settings Guide](doc/API_SETTINGS.md)                     | Complete API configuration for all providers |
| [Architecture Overview](doc/ARCHITECTURE.md)                  | System design, data flow, component details  |
| [STT Implementation Status](doc/STT_IMPLEMENTATION_STATUS.md) | Complete status of all 18 STT providers      |

---

## License

This project is proprietary software.
