#!/usr/bin/env bash
# Relaunch runClient against the local GrimAC server with the freshly built
# payload, and block until it is in the world. Used by the overnight loop:
# every visual check needs the client rebuilt, restarted and joined, and doing
# that by hand three times is three chances to assert against a stale DLL.
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
# Test loops run the local-only build; the DLL lands in dist/.
DLL="$ROOT/dist/cobble.dll"
cd "$ROOT/testenv" || exit 1
JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH" \
    ./gradlew runClient --console=plain -Ppayload="$DLL" \
    -Pserver=localhost:25565 > /tmp/runclient.log 2>&1
