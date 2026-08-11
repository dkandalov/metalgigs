#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
export GRADLE_OPTS="--enable-native-access=ALL-UNNAMED"
./gradlew -q test
