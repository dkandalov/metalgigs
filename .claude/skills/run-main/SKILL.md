---
name: run-main
description: Run the app's main entry point to scrape gigs, render the HTML page, or both. Use when asked to run the app, scrape gigs, or render the gigs page.
---

Run `.claude/scripts/run-main.sh [scrape|render|all]` from the project root using the Bash tool.

- `scrape` fetches gigs from all three venues and writes `gigs.ndjson`.
- `render` reads the existing `gigs.ndjson` and writes `gigs.html` (no network calls).
- `all` (also the default when no argument is given) does both.

Report what was produced and surface any errors from the Gradle output.
