# 5. A gig is identified by the url it lives at, and a venue that moves one is paired as the listing is read

Accepted. Recorded 2026-08-25, describing `GigCorrection.kt` and the url each source builds.

## Context

Nothing upstream gives a gig an identifier we did not derive ourselves (ADR 1), so a gig is identified by
where it lives. That works until a venue edits a gig it has published: both Cart and Horses and dice.fm
build a url out of the title, so swapping in a support act moves the gig, and the log then holds one night
twice. Only the run that first misses the old url can tell - by the next one the old gig is no longer
current and there is nothing left to compare. Several sources have no per-gig url at all, or several that
would each do.

## Decision

### Choosing the url

| Source | Takes | Because |
| --- | --- | --- |
| Dice | the url dice.fm redirects to | it serves event pages under a perm_name prefixed with a short code of its own (`2wqb7p-its-never-over-…`) and answers the bare perm_name with a 308; no API hands the prefix out, so taking the listed perm_name would move every gig to a url dice.fm does not serve. The short `link.dice.fm` ticketing link is opaque and reused |
| AMG | the ticket url up to its `?` | no per-gig page exists; one gig lists several tickets whose urls differ by marketing params in an unstable order, so identity would keep changing. Stripping leaves the platform's event id, stable and still a working link |
| AMG | the *first ticket that has* a link | a ticket entry exists before its link does - ADÉLA at O2 Academy Brixton listed two entries with url `""` ahead of the branded one, and neither `isVisible` nor `ticketStatus` separates them: the blank ones were visible and on sale |
| OVO Arena | the page url plus the date as a fragment | a run of nights is one event page listed once per night (André Rieu's two September nights share one). Appended to every gig, not only collisions: doing it on collision would rewrite the url of a gig already logged the day a second night is announced |
| The Dev | the venue's Facebook page plus a fragment | there is no per-gig page; the Instagram post is superseded every month where the page is not, so a gig keeps the url it was first logged under |
| DHP | a sold-out gig's "Gig Sold Out" notification | its heading is not a link at all, and the notification points at the same page |

Each is checked against that venue's prefixes by `gigUrlFrom` (ADR 2).

### Pairing a gig its venue has moved

`replacementsIn` decides, as the listing is read, what this run says about a gig the log holds and it no
longer carries. `MissingGig` records it: **`MovedTo`** - the venue redirects, an answer rather than a guess;
**`Gone`** - the page is deleted, though a gig cancelled outright looks identical, so a candidate is still
needed; **`Live`** - the page is still served, so whatever it is, it has not moved.

A redirected url is followed to its target and needs nothing else. A deleted url names nothing, so a gig on
the same night that looks like the missing one stands in - the edits that move a url are small, an added
support band or a settled festival name.

**Looking for that gig comes first, before the url is asked about at all.** A missing gig with nothing like
it listed that night could not have been paired whatever its url said, and the request would be spent to
learn nothing - daily, since a run that pairs nothing records nothing. Cancelled gigs are the bulk of that
and now cost no request.

Looking alike means the titles share enough words *or* the posters match; either arm alone misses a real
move, Signature Brew's LOLA (AUS) returning with a picture Dice re-uploaded two days later, and Cart and
Horses' relisting scoring 0.44 while keeping its poster. Similarity is over a title's words rather than its
characters, so the edit counts once however long it is and "LOLA (AUS) | London" reads as the same gig as
"LOLA (AUS) + Lucky Hit | London". Words of two characters or fewer are dropped - every second title has an
"at", a "the" or a "+". The threshold is ADR 4's.

`missingGigSays` asks the *old url itself* rather than the listing, so a venue keeping the page up answers
for itself. A redirect is followed no further than its own `Location` - the point is where the venue says
the gig went, not what is served there - and that `Location` is resolved against the url asked about, since
half are relative: dice.fm answers with the whole url, Cart and Horses with `/news-offers-events/…`. The
request carries a browser User-Agent, several venues answering without one with a 403.

## Consequences

What this gives up is a redirect's ability to surprise us: a venue that renames a gig past recognition, or
moves it to another night, is no longer asked. The pairing must happen while the listing is read, which is
why it is recorded as `GigReplaced` (ADR 1). And a gig's identity depends on a redirect Dice serves.

## Alternatives rejected

**Similarity alone** - real sittings and ticket types score at or above real moves (ADR 4). **Asking about
the url first** - a request per cancelled gig, daily, to learn nothing. **Following the redirect chain to
what is served** - `Location` is the venue's own statement. **The listed Dice perm_name** - not a page.
**The whole AMG ticket url, or its first ticket** - unstable params; the first ticket often has no link.
**OVO's date only on collision** - it rewrites an already-logged url.
