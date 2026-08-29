# 3. Every listing is checked every run, and a check that names a gig costs its venue the whole listing

Accepted. Recorded 2026-08-25, describing the `metalgigs.validate` package.

## Context

The failures ADR 2 catches are the ones a source can notice while reading. The rest are shapes: a selector
matching a card's container rather than its heading, a date parse returning the same wrong date for every
row, a poster selector now matching the venue's logo. None fails; each returns a value of the right type,
and every gig built from it looks ordinary alone. What gives them away is the listing seen whole - ninety-six
gigs on one day, twenty under one picture - or one gig held against what a real gig looks like. A venue that
lists nothing is hardest: the source leaves in silence, one "0 gig(s) listed" line among every other venue's.

## Decision

`validateGigs` runs every `GigsCheck` over every gig a source listed, not only the new or changed ones: a
broken venue serves the same broken text every run, and a gig logged before a check existed is wrong until
someone is told. It takes `Map<VenueId, List<Gig>>` rather than a flat list, because a venue that listed
nothing is absent from such a list entirely and there would be nothing to notice; the keys cannot fall out
of step with the gigs the way a separate set of ids could. A venue whose source threw never reaches a check,
so an empty list means the page was fetched, read, and matched nothing.

**Naming a gig is what costs the venue its listing.** The gigs decide whether the venue failed, not which
are kept: what a check finds is evidence about the source, and the gigs beside it came off the same
selectors, only falling the right side of a threshold. That rule was `ContaminationCheck`'s before it was
every check's. **A problem naming no gigs is the exception** - an empty listing has nothing to withhold, and
text a venue's own page prints wrong is no reason to drop its gigs. Nothing is logged for a failed venue, so
the cooldown returns within the day.

**A page whose promoter wrote nothing is not a failure.** Copy that would not parse throws where it is read
(ADR 2), so a blank reaching a check is a page read that said nothing, which the classifier judges from the
poster. No check names it - and the two that compare copy skip blanks, or a venue's empty pages would read
as one description shared between them.

A gig is spoken for by the first check to claim it, so a bot wall both misshapen and repeated is reported
once, as the parsing failure it is. Order is therefore by how precisely each names what went wrong:

| Check | The failure it is the only witness to |
| --- | --- |
| `EmptyListingCheck` | a listing selector that stopped matching, returning empty rather than failing |
| `NothingSoonCheck` | a date parse that drifted whole, moving a listing bodily forward |
| `MisshapenGigsCheck` | a selector that swallowed a card or page, a cookie or bot wall |
| `UnparsedTextCheck` | `.html()` where `.text()` was meant, an undecoded field, bytes in the wrong charset |
| `DuplicateGigsCheck` | a paging loop re-serving a page, or a "featured" strip repeating the run |
| `CrowdedDayCheck` | a date parse that collapsed, landing a whole listing on one day |
| `SharedPosterCheck` | a poster selector matching the venue's logo, banner, or a network placeholder |
| `SharedDescriptionCheck` | a selector returning text belonging to the venue rather than to the gig |
| `ContaminationCheck` | page text carrying the nav, footer and cookie notice into every gig |

A check naming no gig neither speaks for one nor is spoken over, so where it sits is only where it reads.
The list is built per run because `NothingSoonCheck` needs today's date, and reading the clock inside a check
would leave nothing testable against a fixed date.

Three name no gig. `EmptyListingCheck` has nothing to withhold, and reads what the log holds to separate a
broken source from a venue yet to announce anything. `NothingSoonCheck` sits so close to real behaviour
(ADR 4) that withholding would drop a real listing every few weeks, silently, leaving newly announced gigs
unlogged for as long as the venue stayed quiet - exactly when it has fewest to spare. `UnparsedTextCheck`
withholds a *title* holding markup but not *descriptions*: the log's only real case is The Black Heart,
whose pages print a Bandcamp embed's code below real copy, and withholding would cost actual metal gigs
over a broken embed.

`SharedDescriptionCheck` tells its bug from its innocent case by the titles: a repeated description is
either the venue's own text or a repeat booking, and every repeat booking in the log titles its repeat
("Leo Kottke - SOLD OUT" against "Leo Kottke", "(NIGHT 1)" against "(NIGHT 2)"). A shared word between every
title separates them, and must survive the venue's typography - "Paper Dress 80s Club" and "Paper Dress 80's
Club" are one night.

`ContaminationCheck` surfaces rather than strips, the fix belonging in that source's `eventPageContent`
(ADR 7). It measures the *fraction* of each gig's words that are shared, not whether any repeats: venues
print short policy lines on every page as genuine content. A phrase must recur across at least half that
venue's gigs, never fewer than two, since one coincidental overlap is not boilerplate; each gig's own
six-word windows are deduped first, so a filler line repeated on one page is not mistaken for something
shared *across* gigs; and the estimate is capped at the gig's word count, since overlapping windows over a
long shared run would otherwise be counted many times over.

## Consequences

A single misshapen gig removes its venue from the day's page - the cost of treating a finding as evidence
about the source. Reports read one problem per reason rather than one per gig. A venue can be named without
losing anything, so the report must be read with that in mind. Nothing witnesses a selector still matching a
container the copy has moved out of: every gig comes back blank, every one is judged from its poster, and no
check has anything to say.

## Alternatives rejected

**Withholding only the named gigs** - the rest came off the same selectors. **A flat list of gigs** - the
venue that listed nothing would be absent, and the failure most needing a report could not be seen.
**Checking only new or changed gigs** - a broken source serves the same text every run, so nothing is ever
new. **Reading the clock inside a check** - it leaves nothing testable against a fixed date.
**Auto-stripping shared text** - it risks eating real copy; the fix belongs in the source's scoping.
