# 14. A venue that changes platform keeps its artwork, and is read from where it now sells

Accepted. Recorded 2026-11-04, describing `AltTicketsVenueGigsSource` and `keepingDhpPosters`.

## Context

On 2026-10-14 The Garage's site answered a 504, and by the next scrape `thegarage.london` and `thegrace.london`
redirected every path to one Linktree, `linktr.ee/thegarageldn`. The page that landed on parsed, held no cards, and
both venues listed nothing for three weeks. The Linktree's only listings are the two rooms' AltTickets venue pages.

A gig is identified by its url (ADR 5), so moving surface moves every gig, and the redirect names nothing listed, so
`replacementsIn` cannot pair them itself. On 2026-10-20 the 125 of 140 upcoming gigs with a same-night AltTickets event
sharing a word of their title were moved by hand: a `GigObserved` under the AltTickets url carrying the same title,
date, poster and description, plus a `GigReplaced`, so each kept its genre (ADR 1, ADR 12). The 15 left over stayed on
their DHP urls, AltTickets listing nothing for them on that night.

AltTickets is the poorer source. Of the 150 gigs listed on 2026-11-04, 38 event pages carried copy of 80 characters or
more, 7 less than that, and 104 nothing; its posters are 400x400 where DHP's ran to 1024, and 18 are its logo.

## Decision

**A gig the log holds under a DHP poster keeps that poster and its description**, whatever AltTickets lists, whether
it is found under the same url or the one a door-time change has just moved it to. Everything else is what is listed.
A gig's title is listed like any other and takes AltTickets' spelling.

Because `newOrChangedGigs` compares a whole gig, leaving AltTickets' values in would log every migrated gig as changed
on the first scrape and replace what it published. The step sits in the scrape rather than a source, a source having
no sight of the log, and after the replacements, which are what name the url a moved gig came from. The gig it
produces then holds a DHP poster under its new url, so nothing further has to remember the move.

The poster's bytes are in `.image-cache`, keyed by the url it always had, so nothing has to reach a dead site.

## Consequences

`scrapeGigs` withholds by venue rather than by comparing gigs, the gig logged no longer being the one validated -
the same set, `withheld` always having been whole venues. Validation reads what was listed, not what is kept.

A gig new to AltTickets is judged from its poster more often than most (ADR 12), and one of the 18 under the logo is
judged from nothing; its copy reaches the classifier once it is written. `keepingDhpPosters` names two hosts that are
gone, and stops mattering as these gigs pass. If the DHP sites return nothing here brings them back.

## Alternatives rejected

**Letting AltTickets overwrite them** - simpler, for a smaller poster and a blank description on every migrated gig.
**The 1170x375 banner** - ADR 9. **Leaving the DHP source running beside it** - it has nothing left to read.
