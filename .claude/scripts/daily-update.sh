#!/usr/bin/env bash
# Runs the daily update and publishes the result: scrape, classify, render, then commit whatever
# changed and push. Safe to run repeatedly - daily-update itself does nothing if the log already
# holds a render for today, and this script then finds nothing to commit.
#
# Pass "force" to update again on a day that's already been done.
set -euo pipefail
cd "$(dirname "$0")/../.."

# a scheduled run inherits none of the interactive shell's environment, so fall back to a file the
# launchd agent can rely on. Kept out of the plist deliberately: those are world-readable by default
if [ -z "${ANTHROPIC_API_KEY:-}" ] && [ -f "$HOME/.metalgigs.env" ]; then
    # shellcheck disable=SC1091
    . "$HOME/.metalgigs.env"
fi

if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
    echo "ANTHROPIC_API_KEY is not set, and classify makes a real API call per gig." >&2
    echo "Export it, or put it in ~/.metalgigs.env (chmod 600) for unattended runs - ~/.zshrc," >&2
    echo "where it currently lives, is not read by a non-interactive shell or by launchd." >&2
    exit 1
fi

# checked up front because render doesn't fail without it: image conversion is reported per gig and
# the render still completes, so an unattended run would publish a page of missing images and push
# it. A scheduled job has nobody watching the output, so this has to be fatal rather than noisy
if ! command -v "${MAGICK_BINARY:-magick}" >/dev/null 2>&1; then
    echo "ImageMagick (${MAGICK_BINARY:-magick}) not found on PATH, and render needs it to publish images." >&2
    echo "PATH is currently: $PATH" >&2
    echo "For launchd, put /opt/homebrew/bin on the agent's PATH or set MAGICK_BINARY." >&2
    exit 1
fi

output=$(mktemp)
trap 'rm -f "$output"' EXIT

# set -e aborts here if any step fails, so nothing is committed or pushed from a broken run. That
# includes render refusing to publish while gigs are unclassified: the scrape and classify results
# are already safe in the append-only log, and the next run picks them up.
if [ $# -gt 0 ]; then
    .claude/scripts/run-main.sh "daily-update $1" | tee "$output"
else
    .claude/scripts/run-main.sh "daily-update" | tee "$output"
fi

# only the files the update produces. Never `git add -A`: this repo also carries untracked local
# editor and tool config that has no business being committed
git add -A events.ndjson index.html images/

if git diff --cached --quiet; then
    echo
    echo "Nothing to commit."
else
    summary=$(grep -m1 '^Rendered ' "$output" || echo "no render this run")
    git commit -q -m "Daily update: ${summary}"
    echo
    echo "Committed: $(git log --oneline -1)"
fi

# pushes any earlier unpushed commits too, not just this run's - that's the intent, and it's a
# no-op when there's nothing ahead of the remote
git push
