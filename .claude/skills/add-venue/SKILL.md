---
name: add-venue
description: Add a new London venue as a scrapeable GigsSource - inspect the real listing page live, write the scraper with a live-verified test, scope the classifier's description extraction, and wire it into the CLI. Use when asked to add support for a venue's website or listing page, or to add a new venue to the scraper.
---

1. **Fetch the real listing page live** (`curl -sS -L <url>`) before writing any selector. A 403 or empty
   body usually means it wants a browser `User-Agent`, not that it needs JS rendering. Check for
   pagination explicitly - a "page 2" link or an API cursor field - rather than trusting one page's gig
   count to be the whole listing.

2. **Write the `GigsSource`.** One `Venue` constant declared just above its class, reused by the class,
   its tests, and any delegates. If several venues share a platform, write the shared logic once with
   site-specific bits as constructor parameters, then a thin delegate class per venue (see
   `DhpVenueGigsSource`, `AmgVenueGigsSource`, `DiceVenueGigsSource` for the pattern). Don't set
   `description` here - it isn't available yet; `scrapeGigs` fills it in later, only for gigs it's
   actually going to log. Watch for: date ranges that can cross a month or year boundary (the year is
   often only written once, on the end date); a listing thumbnail that's smaller than a `srcset` or
   CDN-param alternative also on the page; sold-out/cancelled gigs, which are still real listings.

3. **Wire it into `allSources` in `Main.kt` and `allVenues` in `Venue.kt`**, and into the venue list in
   the `run-main` skill. A gig carries only its venue's `VenueId`, so a venue missing from `allVenues`
   fails every render and every message naming it; `VenueTest` catches that for anything already logged.

4. **Write the scraping test** with real `first`/`last` values from an actual run, never placeholders.
   `cachedClient()` replays from a recorded fixture and refuses the network unless `RECORD_TRAFFIC=1` is
   set, so the first run needs `RECORD_TRAFFIC=1 ./gradlew test --tests "..."` (the `run-tests` skill's
   script doesn't set this). Scan the recorded fixture for anything secret-shaped before committing it -
   response bodies are auto-redacted, but a credential sent as a request header isn't.

5. **Scope the event page text.** Each source parses its own event pages: give the class an
   `internal fun eventPageContent(page: Document)` and pass it to `fetchDescription`. Expect to need to
   scope it to a container - most venues do, usually because the whole-page text picks up nav/footer
   boilerplate, or a related-content widget sits inside the real container rather than beside it. Put it
   on the shared scraper if the venue has one, and test it there, since the delegating class doesn't
   expose it. Prefer naming the container(s) that hold real content over excluding boilerplate piece by
   piece - a second, differently-shaped bit of boilerplate is easy to miss the first time. Write a
   scoping test the same shape as the existing ones in `GigsSourceTest`: synthetic HTML with real
   content next to a piece of the venue's actual boilerplate, asserting `eventPageContent` returns one
   and not the other. Extraction that matches nothing is worse than taking the whole page: it reaches
   the gig as a blank description, so a gig with a poster is judged from that alone and a gig without
   one is refused and left Pending.

6. **Verify against the real thing, not a sample.** `scrapeGigs` runs `validateGigs` over every gig a
   source lists and reports what looks like a parsing failure, a description repeated word for word,
   or a venue's gigs sharing suspiciously much text - read the report if the new venue appears in it.
   Also worth reading a couple of gigs' *full* captured `description` directly, not just a prefix. Finish
   with a real scrape of just the new venue (`scrape <venue-id> force`) and confirm the count matches the
   test.

## Traps

- `Gig.description` defaults to `""`, never `null` - check blankness, not nullness.
- A shared-platform venue (dice.fm, DHP, AMG) may need a numeric or opaque venue id rather than a URL -
  look for it in that platform's own API response before guessing.
