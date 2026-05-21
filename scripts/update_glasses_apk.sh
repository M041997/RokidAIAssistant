#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"

echo "Building glasses debug APK..."
JAVA_HOME="${JAVA_HOME:-$ROOT_DIR/.jdk}" \
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle}" \
    "$ROOT_DIR/gradlew" :glasses-app:assembleDebug

"$ROOT_DIR/scripts/fill_rokid_uploader.sh"
