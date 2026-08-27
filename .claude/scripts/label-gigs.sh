#!/usr/bin/env bash
# Drives the labelling loop for the classifier evaluation set: list the gigs awaiting labels, or
# record the labels waiting in build/pending-labels.txt. A main() rather than a test, because
# recording writes to the committed set and the suite has no business doing that.
set -euo pipefail
cd "$(dirname "$0")/../.."
export GRADLE_OPTS="--enable-native-access=ALL-UNNAMED"
# omitted rather than passed empty, which gradle rejects before main() can print its usage
./gradlew -q labelGigs ${*:+--args="$*"}
