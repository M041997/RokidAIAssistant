# Pixel/Rokid Setup Progress

Date: 2026-05-20

## Goal

Get Rokid AI Assistant running with:

- Pixel 7 as the Android companion phone
- Rokid glasses running the glasses APK
- Gemini API key configured from local build settings
- Glasses microphone audio recorded, saved, played back, and transcribed
- Later target: real-time translation captions

## Hardware Used

- Original TCL/TLC Android phone was abandoned because Bluetooth/upload behavior was unreliable.
- Google Pixel 7 is now the active Android companion.
- Rokid glasses are installed with the custom glasses app via RokidApkUploader.

## Local Build Setup

The repo did not read `.env` directly. The Android build reads `local.properties`.

Actions completed:

- Converted the `.env` Gemini key into `local.properties` as `GEMINI_API_KEY`.
- Added `sdk.dir=/home/boss/Android/Sdk` to `local.properties`.
- Installed a local Temurin JDK 21 under `.jdk/`.
- Added `.env` and `.jdk/` to `.gitignore`.
- Made `gradlew` executable.

Build command used:

```bash
JAVA_HOME=/home/boss/RokidAIAssistant/.jdk \
GRADLE_USER_HOME=/home/boss/RokidAIAssistant/.gradle \
./gradlew :phone-app:assembleDebug :glasses-app:assembleDebug --no-daemon
```

## APKs

Phone APK:

```text
phone-app/build/outputs/apk/debug/phone-app-debug.apk
```

Glasses APK:

```text
glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

Pixel Downloads copy for RokidApkUploader:

```text
/sdcard/Download/glasses-app-debug.apk
```

## Pixel 7 Setup

Pixel 7 ADB device:

```text
2B231FDH2009ZV
```

Installed on Pixel 7:

- Rokid AI Assistant phone app
- RokidApkUploader v1.0.3

RokidApkUploader release verified earlier:

```text
RokidApkUploader-v1.0.3.apk
sha256:560889f97f547188b804ab0dee195a7ffb462609fa3cc998a173b4d181738ef2
```

## Upload Flow That Worked

Use RokidApkUploader on Pixel:

1. Disconnect glasses from other phones.
2. Turn off Bluetooth on iPhone/TCL during upload attempts.
3. Fold right glasses leg.
4. Press capture button 3 times quickly. Do not hold.
5. In RokidApkUploader, select `Downloads/glasses-app-debug.apk`.
6. Tap Scan.
7. When found, unfold right leg.
8. Tap Upload.

The uploader reported:

```text
APK installed successfully
```

## Committed Checkpoint

Committed on `main`:

```text
a6dfb43 Fix glasses recording capture and playback
```

That commit includes:

- Ignore `.env` and `.jdk/`
- Make `gradlew` executable
- Save glasses recordings immediately on phone before STT succeeds
- Update saved recording later with transcript and AI response when available
- Fix recording timer scope so duration does not stay at `00:00`
- Add real `MediaPlayer` playback in recording detail screen
- Improve glasses recording stop flow so it does not immediately cancel the recorder job

## Current Uncommitted Follow-Up Changes

After `a6dfb43`, three files were changed while diagnosing silent audio:

- `glasses-app/src/main/java/com/example/rokidglasses/viewmodel/GlassesViewModel.kt`
- `phone-app/src/main/java/com/example/rokidphone/service/ai/BaseAiService.kt`
- `phone-app/src/main/java/com/example/rokidphone/service/ai/GeminiService.kt`

These changes:

- Revert glasses recording source back from `VOICE_RECOGNITION` to `MIC`
- Add `normalizePcm16Le(...)` to amplify quiet PCM before STT
- Send normalized PCM to Gemini transcription

These have been built and the updated phone app was installed on Pixel. The updated glasses APK was copied to Pixel Downloads, but should be uploaded to the glasses again if not already done after this change.

## What Works

- Pixel 7 is recognized by ADB.
- Phone app installs on Pixel.
- RokidApkUploader installs on Pixel.
- Glasses APK can be uploaded to the glasses.
- Glasses and Pixel can connect in app.
- Glasses recording timer now ticks up.
- Phone saves a `Glasses Recording` entry even when STT fails.
- Saved glasses WAV files are structurally valid WAV files.

## What Is Still Broken

### Playback

Playback is still reported as not audible from the phone app.

Important nuance:

- The app previously had no real playback implementation; that was fixed with `MediaPlayer`.
- However, the latest inspected Pixel recording was digital silence, so playback can still produce no audible sound even if the player itself works.

### Speech Recognition

Gemini still returns:

```text
Unable to recognize speech, please try again
```

The latest pulled Pixel recording was valid but silent:

```text
Duration: 00:00:05.12
Format: pcm_s16le, 16000 Hz, mono
mean_volume: -91.0 dB
max_volume: -91.0 dB
```

That means STT is failing because the glasses app sent effectively silent PCM.

Earlier TCL-era recording had real signal:

```text
Duration: 00:00:06.14
Format: pcm_s16le, 16000 Hz, mono
mean_volume: -32.8 dB
max_volume: -12.0 dB
```

The silence appeared after switching the glasses source to `VOICE_RECOGNITION`; this has now been reverted to `MIC`, but the updated glasses APK needs to be uploaded and tested.

## Next Debugging Steps

1. Upload the latest `Downloads/glasses-app-debug.apk` to the glasses again with RokidApkUploader.
2. Reconnect Pixel app and glasses app.
3. Record a new glasses sample while speaking loudly for 5 seconds.
4. Pull the newest WAV from the Pixel:

```bash
/home/boss/Android/Sdk/platform-tools/adb -s 2B231FDH2009ZV shell run-as com.example.rokidphone ls -lt files/recordings
```

5. Inspect volume:

```bash
/home/boss/Android/Sdk/platform-tools/adb -s 2B231FDH2009ZV exec-out run-as com.example.rokidphone cat files/recordings/<LATEST_FILE>.wav > /tmp/pixel-rokid-latest.wav
ffprobe -hide_banner /tmp/pixel-rokid-latest.wav
ffmpeg -hide_banner -i /tmp/pixel-rokid-latest.wav -af volumedetect -f null -
```

Expected after the MIC rollback:

- Not `-91 dB`
- Ideally `max_volume` somewhere above `-35 dB`

If the file is still silent:

- Check microphone permission on the glasses app.
- Try alternate `MediaRecorder.AudioSource` values on glasses, probably `CAMCORDER` or `UNPROCESSED`.
- Add glasses-side sample peak logging before send.
- Display/send an error if recorded PCM peak is near zero instead of silently sending it to STT.

If the file has real speech but Gemini still fails:

- Keep PCM normalization.
- Relax Gemini transcription prompt.
- Try a dedicated STT provider or Gemini model variant.
- Confirm Pixel app speech language is `English / en-US` for English tests.

## Current Practical State

The system is installed and connected, but the audio pipeline is not yet healthy. The immediate blocker is confirming the newest glasses build sends non-silent microphone PCM after reverting to `MIC`.
