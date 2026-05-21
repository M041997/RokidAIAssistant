#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT_DIR/scripts/fill_rokid_uploader.sh" \
    --apk-path "$ROOT_DIR/bluetooth-launcher-app/build/outputs/apk/debug/bluetooth-launcher-app-debug.apk"
