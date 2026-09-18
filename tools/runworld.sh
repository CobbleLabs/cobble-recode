#!/usr/bin/env bash
# Launch runClient connected to local GrimAC server
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME_DIR="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
DLL="${1:-$ROOT/dist/cobble.dll}"
DATA="$(powershell -NoProfile -Command '(Join-Path $env:APPDATA "cobble").Replace("\","/")')"
rm -f "$DATA/cobble.log" "$DATA/cobble.control.out" "$DATA"/*.bmp
cd "$ROOT/testenv" || exit 1
JAVA_HOME="$JAVA_HOME_DIR" PATH="$JAVA_HOME_DIR/bin:$PATH" \
    ./gradlew runClient --console=plain -Ppayload="$DLL" -Pserver=localhost:25565