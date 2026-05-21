#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH=""
DEVICE_APK_PATH=""
UPLOADER_PACKAGE="io.github.miniontoby.rokidapkuploader"
UPLOADER_ACTIVITY="$UPLOADER_PACKAGE/.MainActivity"
PHONE_APP_PACKAGE="com.example.rokidphone"
SERIAL_FILE="$ROOT_DIR/debug_frames/rokid_serial.txt"
APK_FILE_NAME="glasses-app-debug.apk"

launch_uploader=true
push_apk=true
tap_upload=false
apk_path_set=false
device_apk_path_set=false
reset_bluetooth=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk-path)
            APK_PATH="$2"
            APK_FILE_NAME="$(basename "$APK_PATH")"
            DEVICE_APK_PATH="/sdcard/Download/$APK_FILE_NAME"
            apk_path_set=true
            shift
            ;;
        --device-apk-path)
            DEVICE_APK_PATH="$2"
            APK_FILE_NAME="$(basename "$DEVICE_APK_PATH")"
            device_apk_path_set=true
            shift
            ;;
        --no-launch)
            launch_uploader=false
            ;;
        --skip-apk-push)
            push_apk=false
            ;;
        --upload)
            tap_upload=true
            ;;
        --reset-bluetooth)
            reset_bluetooth=true
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 2
            ;;
    esac
    shift
done

detect_main_glasses_apk() {
    local preferred_apk="$ROOT_DIR/glasses-app/build/outputs/apk/debug/glasses-app-debug.apk"
    if [[ -f "$preferred_apk" ]]; then
        printf '%s' "$preferred_apk"
        return 0
    fi

    find "$ROOT_DIR/glasses-app/build/outputs/apk" \
        -type f \
        -name '*.apk' \
        -printf '%T@ %p\n' 2>/dev/null |
        sort -nr |
        awk 'NR == 1 { sub(/^[^ ]+ /, ""); print; exit }'
}

resolve_apk_defaults() {
    if [[ "$apk_path_set" == false ]]; then
        APK_PATH="$(detect_main_glasses_apk)"
    fi

    if [[ "$push_apk" == true && -z "$APK_PATH" ]]; then
        echo "No main glasses APK found under glasses-app/build/outputs/apk." >&2
        echo "Run scripts/update_glasses_apk.sh to build it, then this helper will auto-pick it." >&2
        exit 1
    fi

    if [[ "$push_apk" == true && ! -f "$APK_PATH" ]]; then
        echo "Missing APK: $APK_PATH" >&2
        echo "Run ./gradlew :glasses-app:assembleDebug first, or use scripts/update_glasses_apk.sh." >&2
        exit 1
    fi

    if [[ "$device_apk_path_set" == false ]]; then
        if [[ -n "$APK_PATH" ]]; then
            APK_FILE_NAME="$(basename "$APK_PATH")"
        fi
        DEVICE_APK_PATH="/sdcard/Download/$APK_FILE_NAME"
    else
        APK_FILE_NAME="$(basename "$DEVICE_APK_PATH")"
    fi
}

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

prepare_phone_connection_state() {
    echo "Stopping apps that can hold the glasses connection..."
    adb shell am force-stop "$PHONE_APP_PACKAGE" >/dev/null 2>&1 || true
    adb shell am force-stop "$UPLOADER_PACKAGE" >/dev/null 2>&1 || true
    adb shell am force-stop com.google.android.documentsui >/dev/null 2>&1 || true

    if [[ "$reset_bluetooth" == true ]]; then
        echo "Resetting Pixel Bluetooth adapter..."
        adb shell cmd bluetooth_manager disable >/dev/null
        adb shell cmd bluetooth_manager wait-for-state:STATE_OFF >/dev/null 2>&1 || sleep 2
        adb shell cmd bluetooth_manager enable >/dev/null
        adb shell cmd bluetooth_manager wait-for-state:STATE_ON >/dev/null 2>&1 || sleep 3
        sleep 2
    fi
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
resolve_apk_defaults
prepare_phone_connection_state

if [[ "$push_apk" == true ]]; then
    echo "Using APK: $APK_PATH"
    echo "Copying APK to $DEVICE_APK_PATH..."
    adb push "$APK_PATH" "$DEVICE_APK_PATH" >/dev/null
else
    echo "Using APK already on Pixel: $DEVICE_APK_PATH"
fi

if [[ "$launch_uploader" == true ]]; then
    echo "Launching Rokid APK uploader..."
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
