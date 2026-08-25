# 2. A source fails rather than standing something plausible in

Accepted. Recorded 2026-08-25, describing the helpers in `GigsSource.kt`.

## Context

A scraper's failures are quiet. Jsoup returns an empty selection rather than null and its `text()` is
`""`; a JSON field a site stopped populating arrives as `""`; a 404 has a body like any other and parses
to nothing exactly as a redesign does. Each is an ordinary value that builds a `Gig` looking fine, then
logged, classified and published - with nothing downstream able to tell a description a venue never wrote
from one it wrote empty.

## Decision

A source that cannot read what it is looking for throws, and the venue's whole scrape fails for the run.
Nothing is logged, so the cooldown reads it as unscraped and returns within the day. `""` then means
exactly one thing: a page that was read and had nothing to say.

| Helper | Refuses |
| --- | --- |
| `fetchPage` | a non-successful status, so a 404's body is never parsed as markup |
| `fetchDescription` / `descriptionFrom` | a `null` from the content selector, naming the page |
| `Elements.textOrNull` | an empty selection's `""`, which would pass for a page that says nothing |
| `posterUrlFrom` | a blank or absent image url, naming the gig that has none |
| `gigUrlFrom` | a url outside the prefixes this venue's gigs live under |

`fetchPage` tells a page that won't open from one whose markup changed; without it a 404 surfaces as "the
venue's selectors may no longer match it", sending the reader after selectors that are fine.

`gigUrlFrom` catches a listing selector that has *widened* - such a selector returns the nav, the footer,
the "more events" rail rather than nothing, and the url is the one part of a stray link that cannot look
right: a title, date and poster off a footer are plausible strings, a url is a fact about where the thing
lives. Prefixes are declared per source as the url is built, so the answer sits beside the selector it
guards; several span more than one, the AMG venues linking to Ticketmaster under either scheme and
falling back to Gigantic.

Two escape hatches, both narrow. **A named gig may be lost**: `gigOrSkipped` skips only urls the source
names, one each at Windmill Brixton and The Fiddler's Elbow. **A normal end-of-life state may be
dropped**: an AMG event whose ticket sales have closed lists no ticket link, leaving neither identity nor
a link worth rendering, so those are counted, printed and dropped.

A source may stand in what it can defend (DHP's four poster places, ADR 9), and checks a site's answer
where trusting it would let a bad walk run on: DHP's guide answers a wrong content type with months dated
`data-year="0"`, so returned months are checked to be later than the one asked for; Dice's redirect is
checked to land on an event page; AMG's event page is checked to render a hero.

## Consequences

A broken venue disappears from the run rather than publishing wrong gigs, returning tomorrow to break
again until someone looks; the page keeps the gigs the log holds. One broken gig costs the venue's whole
listing - deliberate, and stated in full in ADR 3. `titleFrom` is blind to line breaks and control
characters (ADR 6), so a selector matching a card's container still fails at `GigTitle`.

## Alternatives rejected

**A blank description** - indistinguishable from a page that said nothing, and the classifier would judge
on the title alone with nobody knowing. **Central url prefixes** - checking as the url is built keeps the
prefix beside its selector. **Dropping any gig that fails** - a failure is evidence about the source, and
the gigs beside it came off the same selectors; only urls the source names may be lost that way.
