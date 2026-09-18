#!/usr/bin/env bash
# Development driver for the Cobble Recode public build (local-only, no auth).
#
#   tools/dev.sh build            rebuild payload + injector
#   tools/dev.sh run              launch runClient with the payload autoloaded
#   tools/dev.sh wait             block until the payload reports its first frame
#   tools/dev.sh stop             unload cleanly, then kill the client
#   tools/dev.sh shot <name> <commands separated by ';'>
#
# `shot` writes build/shots/<name>.png.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME_DIR="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
CMAKE="C:/Program Files/CMake/bin/cmake.exe"
BUILD="$ROOT/build/native"
# The payload keeps its runtime files in %APPDATA%\\cobble, not beside the DLL.
DATA="$(powershell -NoProfile -Command '(Join-Path $env:APPDATA "cobble").Replace("\\","/")')"

build() {
    mkdir -p "$BUILD"
    "$CMAKE" -S "$ROOT" -B "$BUILD" -A x64 2>&1 |
        grep -E "CMake Error|Generating done|Configuring done" || true
    "$CMAKE" --build "$BUILD" --config Release 2>&1 |
        grep -E ": error|: fatal error|-> C:" || true
}

case "${1:-}" in
build)
    build
    ;;

run)
    DLL="$ROOT/dist/cobble.dll"
    if [ ! -f "$DLL" ]; then
        echo "no payload at $DLL - run: tools/dev.sh build"
        exit 1
    fi
    rm -f "$DATA/cobble.log" "$DATA/cobble.control.out" "$DATA"/*.bmp
    cd "$ROOT/testenv" || exit 1
    JAVA_HOME="$JAVA_HOME_DIR" PATH="$JAVA_HOME_DIR/bin:$PATH" \
        ./gradlew runClient --console=plain -Ppayload="$DLL"
    ;;

wait)
    for _ in $(seq 1 120); do
        if [ -f "$DATA/cobble.log" ] &&
            grep -q "first frame" "$DATA/cobble.log" 2>/dev/null; then
            grep -E "ERROR|first frame" "$DATA/cobble.log" | tail -5
            exit 0
        fi
        sleep 2
    done
    echo "timed out waiting for the first frame"
    exit 1
    ;;

stop)
    printf 'unload' > "$DATA/cobble.control"
    sleep 2
    powershell -NoProfile -Command \
        "Get-Process java -ErrorAction SilentlyContinue | Where-Object { \$_.MainWindowTitle -like '*Minecraft*' } | Stop-Process -Force"
    sleep 2
    ;;

shot)
    name="${2:-shot}"
    script="${3:-shot ${name}.bmp}"
    powershell -NoProfile -ExecutionPolicy Bypass -File "$ROOT/tools/shoot.ps1" \
        -Script "$script" -Png "build/shots/${name}.png" -Dist "$ROOT/dist"
    ;;

*)
    sed -n '2,15p' "${BASH_SOURCE[0]}"
    ;;
esac
