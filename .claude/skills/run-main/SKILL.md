---
name: run-main
description: Run the app's main entry point to scrape gigs, classify their genre, list unclassified gigs, manually override a gig's genre, render the HTML page, or all of scrape+classify+render. Use when asked to run the app, scrape gigs, classify gigs, list unclassified gigs, override/correct a gig's genre, or render the gigs page.
---

Run `.claude/scripts/run-main.sh [scrape [venue-key...]|classify|unclassified|override <url> <metal|unclassified>|render [all]|all]` from the project root using the Bash tool, e.g. `.claude/scripts/run-main.sh override https://example.com/event metal` (the script joins all its args into one `--args=...` string for `gradlew run`, so passing them as separate words or one quoted string both work — this also means venue keys and other args must not contain spaces, since gradlew resplits on whitespace).

- `scrape [venue-key...]` fetches gigs and appends a `GigObserved` entry per gig to `events.ndjson` (the append-only event log; never overwritten). With no venue keys, scrapes all six venues; with one or more keys (e.g. `scrape dome underworld`), scrapes only those. Known keys: `cart-and-horses`, `new-cross-inn`, `our-black-heart`, `underworld`, `dome`, `blondies`. An unknown key fails fast with the list of valid keys.
- `classify` projects the current gigs from `events.ndjson`, fetches the event page for any gig not yet classified, and appends a `GigClassified` entry (`genre`, `matchedKeywords`, `source = Keywords`) for each.
- `unclassified` projects the current gigs from `events.ndjson` and prints the ones whose latest classification isn't `Metal` (including gigs never classified at all), grouped by venue with a per-venue count, date/title/url per gig — with a trailing total count. No network calls.
- `override <url> <metal|unclassified>` appends a `GigClassified` entry with `source = User` for the gig with that url, asserting its genre directly. Since it's a `GigClassified` entry like any other, a later `classify` run will treat it as already classified and won't reclassify it — the override sticks. No network calls.
- `render [all]` projects the current gigs from `events.ndjson` and writes `gigs.html` (no network calls). By default shows only gigs whose latest classification is `Metal` — gigs never classified, or classified `Unclassified`, are skipped. Pass `all` (e.g. `render all`) to show every current gig regardless of classification.
- `all` (also the default when no argument is given) runs `scrape`, then `classify`, then `render` — it does not include `unclassified` or `override`, which are standalone.

Report what was produced and surface any errors from the Gradle output.
