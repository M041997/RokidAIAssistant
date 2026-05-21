#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="$ROOT_DIR/glasses-app/build/outputs/apk/debug/glasses-app-debug.apk"
DEVICE_APK_PATH="/sdcard/Download/glasses-app-debug.apk"
UPLOADER_PACKAGE="io.github.miniontoby.rokidapkuploader"
UPLOADER_ACTIVITY="$UPLOADER_PACKAGE/.MainActivity"
SERIAL_FILE="$ROOT_DIR/debug_frames/rokid_serial.txt"
APK_FILE_NAME="glasses-app-debug.apk"

launch_uploader=true
push_apk=true
tap_upload=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-launch)
            launch_uploader=false
            ;;
        --skip-apk-push)
            push_apk=false
            ;;
        --upload)
            tap_upload=true
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 2
            ;;
    esac
    shift
done

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

read_serial_number() {
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

    if [[ -n "$serial_number" ]]; then
        printf '%s' "$serial_number"
    fi
}

fill_serial_number() {
    local serial_number="$1"
    if [[ -z "$serial_number" ]]; then
        echo "No serial file found at debug_frames/rokid_serial.txt; leaving serial blank."
        return 0
    fi

    echo "Filling serial number..."
    wait_for_focus "$UPLOADER_PACKAGE" 24
    sleep 0.5
    adb shell input tap 540 158
    sleep 0.2
    adb shell input keyevent CTRL+A >/dev/null
    sleep 0.1
    adb shell input text "$serial_number"
    adb shell input keyevent BACK >/dev/null
    sleep 0.4
}

select_apk() {
    echo "Selecting APK..."
    wait_for_focus "$UPLOADER_PACKAGE" 24
    sleep 0.5
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
}

echo "Checking for connected Android device..."
adb get-state >/dev/null

if [[ "$push_apk" == true ]]; then
    if [[ ! -f "$APK_PATH" ]]; then
        echo "Missing APK: $APK_PATH" >&2
        echo "Run ./gradlew :glasses-app:assembleDebug first, or use scripts/update_glasses_apk.sh." >&2
        exit 1
    fi

    echo "Copying APK to $DEVICE_APK_PATH..."
    adb push "$APK_PATH" "$DEVICE_APK_PATH" >/dev/null
fi

if [[ "$launch_uploader" == true ]]; then
    echo "Launching Rokid APK uploader..."
    adb shell am force-stop "$UPLOADER_PACKAGE" >/dev/null
    adb shell am force-stop com.google.android.documentsui >/dev/null
    adb shell am start -S -n "$UPLOADER_ACTIVITY" >/dev/null

    sleep 1
    adb shell input tap 800 1995 >/dev/null
fi

select_apk
fill_serial_number "$(read_serial_number)"

if [[ "$tap_upload" == true ]]; then
    echo "Tapping upload..."
    adb shell input tap 540 803
else
    echo
    echo "Uploader is ready. Tap UPLOAD APK on the Pixel when ready."
fi
