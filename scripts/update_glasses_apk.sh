#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="$ROOT_DIR/glasses-app/build/outputs/apk/debug/glasses-app-debug.apk"
DEVICE_APK_PATH="/sdcard/Download/glasses-app-debug.apk"
UPLOADER_ACTIVITY="io.github.miniontoby.rokidapkuploader/.MainActivity"

cd "$ROOT_DIR"

echo "Building glasses debug APK..."
JAVA_HOME="${JAVA_HOME:-$ROOT_DIR/.jdk}" \
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle}" \
    "$ROOT_DIR/gradlew" :glasses-app:assembleDebug

echo "Checking for connected Android device..."
adb get-state >/dev/null

echo "Copying APK to $DEVICE_APK_PATH..."
adb push "$APK_PATH" "$DEVICE_APK_PATH"

echo "Launching Rokid APK uploader..."
adb shell am start -n "$UPLOADER_ACTIVITY" >/dev/null

echo
echo "Ready: $DEVICE_APK_PATH"
echo "In the uploader, choose the APK from Downloads if it does not auto-open the picker."
