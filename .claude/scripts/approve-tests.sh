#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

found=0
while IFS= read -r -d '' actual; do
    approved="${actual%.actual}.approved"
    mv -f "$actual" "$approved"
    echo "Approved: $approved"
    found=1
done < <(find src/test/resources -name '*.actual' -print0)

if [ "$found" -eq 0 ]; then
    echo "No .actual files found — nothing to approve."
fi
