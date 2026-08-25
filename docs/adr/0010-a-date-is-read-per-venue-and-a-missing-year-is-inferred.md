# 10. A date is read per venue, only ever the start date, and a year the listing never writes is inferred

Accepted. Recorded 2026-08-25, describing the date handling across `scrape/venues`.

## Context

A gig is a night, and the date is the one field with no fallback: a wrong one puts the gig on the wrong day,
or a year out, and nothing about the gig looks wrong. Few listings write a date a parser would like. Half omit
the year, printing "Fri 21 Aug" with nothing on the page saying which year. Several write a run of nights as a
range with the year only on the end date. Ordinal suffixes are everywhere, comma placement is inconsistent
within one venue, and month abbreviation is inconsistent within one page.

## Decision

Each source parses its venue's format, quoted in the comment beside the pattern, and takes what the site gives
over what it prints:

- **Union Chapel** reads each card's sortable `data-chron`, put there for client-side sorting, rather than
  parsing "Thu 27 May 2027" beside it.
- **Windmill Brixton** reads the date from the event's path, Music Glue putting it at the front of every url,
  a card printing "Sun, Aug 16" with no year near it.
- **Paper Dress Vintage** reads the year off the `data-event-month` attribute wrapping each month's cards.
- **Squarespace venues** read `time.event-date`'s `datetime`.

Where a source parses text the comment carries a real example: Dingwalls' inconsistent commas ("Wednesday 2nd
September 2026", "Tuesday, 8th September 2026", "Saturday 26th September, 2026 (Afternoon Show)"), Electric
Ballroom's yearless "Thursday 13th August", DHP's "Fri.14.Aug.26", Roundhouse's "Wed 12 Aug 26". Ordinal
suffixes are discarded everywhere.

**Only the start date is used** wherever a listing writes a range - Alexandra Palace, Eventim Apollo,
Roundhouse and The O2 all do.

Two shapes of missing year are inferred, both from the listing's own order rather than from today:

**A year counted forward.** Cart & Horses, Electric Ballroom and Islington Assembly Hall print no year at all.
Gigs are listed in date order, so the year increments as the listing crosses back into an earlier month. At
Islington Assembly Hall the pages continue that order, so the count carries across pages rather than
restarting - the boundary falls mid-page-4.

**A year rolled back.** Alexandra Palace, Eventim Apollo and The O2 write a range's year once, on its end date,
which is wrong for a range crossing a calendar year: "11 Dec - 3 Jan 2027" starts in 2026, as does "Dec 28th -
Jan 3rd 2027". The start year is rolled back whenever the start month sorts after the end month. Nothing in the
listings crosses a new year today, which is exactly why it is handled here rather than noticed later.

Time zones are handled where a source gives a timestamp. Dice stamps every event in UTC however late the gig
is, so a door after midnight in London is a day early until read back in the event's own `timezone`. OVO
Arena's `"2026-09-02T18:30:00.0000000"` is a local time with no zone, and AMG's `"2026-08-11T00:00:00Z"`
likewise - the date is the whole of what is wanted.

Two quirks are recorded because they would look like typos. The O2 abbreviates a month except where the
abbreviation saves nothing: the arena writes "Jun" as "June" and "Jul" as "July" while every other month is
three letters, so both forms are tried. Eventim Apollo writes a single date's month in full and a range's
abbreviated.

Because none of this fails loudly, two checks exist for date parsing alone (ADR 3): `CrowdedDayCheck` catches a
parse that has *collapsed*, landing a listing on one day; `NothingSoonCheck` catches one that has *drifted
whole* - a year read off the wrong element, a month rolled on for every row - which moves a listing bodily
forward while leaving the dates spread and ordered, its only mark being that the listing no longer begins near
now. Reading a venue's "on sale soon" strip leaves the same mark.

## Consequences

The year inference depends on the listing staying in date order; a venue reordering its cards would date gigs a
year out, and `NothingSoonCheck` is what would say so. A multi-night run is one gig, dated from its first
night. Every parser here is a venue-specific fact a redesign invalidates, and the quoted format is what a
reader checks against the live page.

## Alternatives rejected

**Parsing a human date where the site carries a machine one** - `data-chron` and `datetime` are the site's own
answer. **Inferring a year from today** - the listings reach eighteen months ahead at OVO and to June 2027 at
The Garage, so only the listing's order settles it. **Taking the year a range writes** - it is the end date's.
**Waiting for the new-year case** - it would be noticed as wrong dates on the published page.
