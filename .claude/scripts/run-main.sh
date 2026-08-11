#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
export GRADLE_OPTS="--enable-native-access=ALL-UNNAMED"
if [ $# -gt 0 ]; then
    ./gradlew -q run --args="$*"
else
    ./gradlew -q run
fi
