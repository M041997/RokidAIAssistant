#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="$ROOT_DIR/glasses-app/build/outputs/apk/debug/glasses-app-debug.apk"
DEVICE_APK_PATH="/sdcard/Download/glasses-app-debug.apk"
UPLOADER_ACTIVITY="io.github.miniontoby.rokidapkuploader/.MainActivity"
SERIAL_FILE="$ROOT_DIR/debug_frames/rokid_serial.txt"
APK_FILE_NAME="glasses-app-debug.apk"

wait_for_focus() {
    local package_name="$1"
    local attempts="${2:-20}"
    for _ in $(seq 1 "$attempts"); do
        if adb shell dumpsys window 2>/dev/null | grep -q "$package_name"; then
            return 0
        fi
        sleep 0.25
    done
    return 1
}

fill_serial_number() {
    if [[ ! -f "$SERIAL_FILE" ]]; then
        return 0
    fi

    local serial_number
    serial_number="$(tr -d '\r\n[:space:]' < "$SERIAL_FILE")"
    serial_number="${serial_number#*=}"
    serial_number="${serial_number%\"}"
    serial_number="${serial_number#\"}"
    serial_number="${serial_number%\'}"
    serial_number="${serial_number#\'}"

    if [[ -z "$serial_number" ]]; then
        return 0
    fi

    echo "Filling serial number..."
    wait_for_focus "io.github.miniontoby.rokidapkuploader" 24
    sleep 0.5
    adb shell input tap 540 158
    sleep 0.25
    adb shell input text "$serial_number"
    adb shell input keyevent BACK >/dev/null
    sleep 0.5
}

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
adb shell am force-stop io.github.miniontoby.rokidapkuploader >/dev/null
adb shell am force-stop com.google.android.documentsui >/dev/null
adb shell am start -S -n "$UPLOADER_ACTIVITY" >/dev/null

sleep 1
adb shell input tap 800 1995 >/dev/null
wait_for_focus "io.github.miniontoby.rokidapkuploader" 24
sleep 1.25

echo "Selecting APK in Android file picker..."
adb shell input tap 930 379
if ! wait_for_focus "com.google.android.documentsui" 12; then
    sleep 0.5
    adb shell input tap 930 379
    wait_for_focus "com.google.android.documentsui" 24
fi
sleep 1.25
adb shell input tap 500 220
adb shell input text "$APK_FILE_NAME"
adb shell input keyevent ENTER
sleep 1.5
adb shell input tap 420 355
sleep 1

fill_serial_number

echo
echo "Ready: $DEVICE_APK_PATH"
echo "Uploader should now have the serial and APK filled. Tap UPLOAD APK when ready."
