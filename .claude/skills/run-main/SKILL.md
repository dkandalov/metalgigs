---
name: run-main
description: Run the app's main entry point to scrape gigs, classify their genre, list unclassified gigs, render the HTML page, or all of scrape+classify+render. Use when asked to run the app, scrape gigs, classify gigs, list unclassified gigs, or render the gigs page.
---

Run `.claude/scripts/run-main.sh [scrape|classify|unclassified|render|all]` from the project root using the Bash tool.

- `scrape` fetches gigs from all five venues and appends a `GigObserved` entry per gig to `events.ndjson` (the append-only event log; never overwritten).
- `classify` projects the current gigs from `events.ndjson`, fetches the event page for any gig not yet classified, and appends a `GigClassified` entry (matched genre keywords, or none) for each.
- `unclassified` projects the current gigs from `events.ndjson` and prints the ones with no matched keywords (including gigs never classified at all) — venue, date, title, url, one per line — with a trailing count. No network calls.
- `render` projects the current gigs from `events.ndjson` and writes `gigs.html` (no network calls). Currently shows every gig regardless of classification.
- `all` (also the default when no argument is given) runs `scrape`, then `classify`, then `render` — it does not include `unclassified`, which is a standalone report.

Report what was produced and surface any errors from the Gradle output.
