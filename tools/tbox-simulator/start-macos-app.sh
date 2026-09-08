#!/bin/zsh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/MOTO-HUB-TBox-Simulator.app"

if [ ! -d "$APP" ]; then
    echo "App not found: $APP" >&2
    echo "Build it first: $ROOT/build-macos-app.sh" >&2
    exit 1
fi

open "$APP"
osascript -e 'tell application id "io.motohub.tbox-simulator" to activate' >/dev/null 2>&1 || true
