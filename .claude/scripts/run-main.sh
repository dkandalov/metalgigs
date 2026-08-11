#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
export GRADLE_OPTS="--enable-native-access=ALL-UNNAMED"
if [ $# -gt 1 ]; then
    # Multiple separate arguments were passed - preserve any literal space within one of them
    # (e.g. a venue name) by encoding spaces (0x1e) and joining arguments with a separator (0x1f)
    # instead of a space, since Gradle's --args value is otherwise re-split on any whitespace
    # before Kotlin ever sees it. main() reverses both steps.
    encoded=()
    for arg in "$@"; do
        encoded+=("${arg// /$'\x1e'}")
    done
    IFS=$'\x1f'
    ./gradlew -q run --args="${encoded[*]}"
elif [ $# -eq 1 ]; then
    # A single argument, possibly containing spaces meant as separators (e.g. "classify 5") - let
    # Gradle's own whitespace splitting handle it, same as always.
    ./gradlew -q run --args="$1"
else
    ./gradlew -q run
fi
