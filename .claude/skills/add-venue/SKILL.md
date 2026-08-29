---
name: add-venue
description: Add a new London venue as a scrapeable GigsSource - inspect the real listing page live, write the scraper with a live-verified test, scope the classifier's description extraction, and wire it into the CLI. Use when asked to add support for a venue's website or listing page, or to add a new venue to the scraper.
---

1. **Fetch the real listing page live** (`curl -sS -L <url>`) before writing any selector. A 403 or empty
   body usually means it wants a browser `User-Agent`, not that it needs JS rendering. Prefer the JSON a
   page feeds itself from over its markup: anything that renders or pages client-side has an endpoint
   behind it, and that endpoint tends to carry what the cards don't - a category to filter on, each
   venue's own id, sometimes the description inline. Distrust a listing that looks complete: a "Load
   More" that arrives already disabled, a month bar that filters what's on the page rather than
   navigating, a cap at a round number with a `load_more` flag and no cursor, a query string the server
   ignores, a dropdown that POSTs instead of linking. Ask for everything in one request where the page
   lets you (a `PageSize` above the number listed); walk an offset-paged endpoint until a batch comes
   back empty; and where there's no cursor at all (a per-month calendar) pick a fixed horizon rather
   than stopping at the first empty month, since a quiet month stops the walk early.

2. **Write the `GigsSource`.** One `Venue` constant declared just above its class, reused by the class,
   its tests, and any delegates. If several venues share a platform, write the shared logic once with
   site-specific bits as constructor parameters, then a thin delegate class per venue (see
   `DhpVenueGigsSource`, `AmgVenueGigsSource`, `DicePartnerVenueGigsSource` for the pattern). Don't set
   `description` here - it isn't available yet; `scrapeGigs` fills it in later, only for gigs it's
   actually going to log. Watch for: date ranges that can cross a month or year boundary (the year is
   often only written once, on the end date), and cards printing no year at all - read it off the event's
   own path, or count it forward as the listing crosses back into an earlier month, carrying that count
   across pages; a month abbreviated everywhere except where abbreviating saves nothing ("June", "July"),
   so read both spellings; a listing thumbnail that's smaller than a `srcset`, `data-src`, CDN-parameter
   or crop-suffix (`-750x450-c-center`) alternative on the same page - take the squarest one from 768px
   up, that being what survives `render`'s own square crop, and check a second `<picture>` isn't a
   generic house image; sold-out/cancelled gigs, which are still real listings; and a genre filter with
   no metal in it, which is a reason to take everything and leave the judgement to the classifier rather
   than to filter.

3. **Wire it into `allSources` in `Main.kt` and `allVenues` in `Venue.kt`**, and into the venue list in
   the `run-main` skill. A gig carries only its venue's `VenueId`, so a venue missing from `allVenues`
   fails every render and every message naming it; `VenueTest` catches that for anything already logged.

4. **Write the scraping test** with real `first`/`last` values from an actual run, never placeholders.
   `cachedClient()` replays from a recorded fixture and refuses the network unless `RECORD_TRAFFIC=1` is
   set, so the first run needs `RECORD_TRAFFIC=1 ./gradlew test --tests "..."` (the `run-tests` skill's
   script doesn't set this). Scan the recorded fixture for anything secret-shaped before committing it -
   response bodies are auto-redacted, but a credential sent as a request header isn't. Assert the gig
   count too, especially for a listing that arrives in one page: with nothing to paginate there's nothing
   to notice, and that assertion is what stands between the site changing and a silently truncated
   listing.

5. **Scope the event page text.** Each source parses its own event pages: give the class an
   `internal fun eventPageContent(page: Document)` and pass it to `fetchDescription`. Expect to need to
   scope it to a container - most venues do, usually because the whole-page text picks up nav/footer
   boilerplate, or a related-content widget sits inside the real container rather than beside it. Put it
   on the shared scraper if the venue has one, and test it there, since the delegating class doesn't
   expose it. Prefer naming the container(s) that hold real content over excluding boilerplate piece by
   piece - a second, differently-shaped bit of boilerplate is easy to miss the first time. Some can only
   be dropped by its wording, though: ticket terms and a venue levy written as siblings of the real copy
   inside one wysiwyg block have no container of their own, and are the entire description on a listing
   whose promoter wrote nothing. Write a scoping test the same shape as the existing ones in
   `GigsSourceTest`: synthetic HTML with real content next to a piece of the venue's actual boilerplate,
   asserting `eventPageContent` returns one and not the other. Extraction that matches nothing throws
   and costs the venue its listing for the run - the one failure a source can see in itself. A block it
   does match and finds empty is a blank description, allowed all the way through: that gig is judged from
   its poster, and left Pending if the poster can't be sent either. A platform that puts the
   description in its own listing (Dice's `raw_description`, a Squarespace excerpt via
   `descriptionFrom = ListingExcerpt`) needs no per-gig request at all - and the page's own meta
   description is never the substitute, being "Buy tickets for X live at Y" on every listing.

6. **Verify against the real thing, not a sample.** `scrapeGigs` runs `validateGigs` over every gig a
   source lists and reports what looks like a parsing failure, a description repeated word for word,
   or a venue's gigs sharing suspiciously much text - read the report if the new venue appears in it.
   Also worth reading a couple of gigs' *full* captured `description` directly, not just a prefix. Finish
   with a real scrape of just the new venue (`scrape <venue-id> force`) and confirm the count matches the
   test.

## Traps

- `Gig.description` defaults to `""`, never `null` - check blankness, not nullness.
- A shared-platform venue (dice.fm, DHP, AMG) may need a numeric or opaque venue id, or the platform's
  own spelling of the venue name, rather than a URL - look for it in that platform's own API response
  before guessing (calling the API unfiltered usually lists every venue), and remember one venue can
  need several ids where its listing covers a second room. Check what a wrong one does: these APIs
  answer an unknown id or name with an empty listing rather than an error, so a silently empty scrape is
  the failure to expect, not an exception.
- A gig's url has to be stable, working and its own. Resolve a redirect the platform serves rather than
  storing the bare path it 308s away from; skip an event whose ticket sales have closed where the ticket
  vendor's url is the identity, since it has neither; and where one event page is listed once per night,
  append each date as a fragment (`.../detail/x-16#2026-09-17`) so the nights are distinct gigs - which
  also collapses a matinee and an evening showing into the one gig the page means.
- JSON holds surprises too: a missing image can arrive as `false` rather than null, so read that field
  as a node and treat anything but a string as no poster; and a "JSON" batch can be one string holding
  an html fragment, or a description written as a JavaScript string literal in an attribute, either of
  which is decoded through the JSON parser before being parsed as what it turns out to be.
- http4k's `form()` sets the body but not `content-type: application/x-www-form-urlencoded`, and a
  WordPress `admin-ajax.php` fills `$_POST` from that header alone - without it the action never arrives
  and it answers 400 without naming the cause. Some sites (dice.fm) 403 anything without a browser-like
  User-Agent, and a source resolving redirects itself needs a client configured not to follow them.
