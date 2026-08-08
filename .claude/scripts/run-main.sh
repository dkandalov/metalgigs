#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
if [ $# -gt 0 ]; then
    ./gradlew run --args="$*"
else
    ./gradlew run
fi
