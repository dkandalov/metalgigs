# 6. A title is tidied as the listing is read, into one spelling per page

Accepted. Recorded 2026-08-25, describing `WithTidiedTitles.kt`, `titleFrom`, and the titles sources compose.

## Context

A title is whatever text a card's heading holds, and every venue writes it its own way: Signature Brew ends
every title "| London" though every venue here is in London; three venues write a sold-out marker three ways;
a bill is a spaced "+" at one venue and a slash off a flyer at another; "FREE ENTRY:" is prepended to the
gig's name. Two things read the title and both are hurt by that - the classifier's prompt, and the word
overlap that pairs a moved gig (ADR 5). A title that flips spelling between runs is also a gig logged as
changed, every run, for as long as it is listed. Separately, `GigTitle` refuses spacing a venue can type.

## Decision

Titles are normalised **as the listing is read**, before the gig is built, so the prompt and the pairing see
the same title. `WithTidiedTitles` decorates a `GigsSource` and makes four changes, each identified by
punctuation rather than wording alone:

- **The trailing city** - "| London", optionally before a cancellation marker - is dropped.
- **A bill's separators** become " / ", spaced so a "+" written against a word is left alone. A title listing
  bands with commas is left entirely: "INHUMAN NATURE, PUPPY, AGNOSY + MORE" joins the last of a list rather
  than separating two acts, where a slash would read as a name.
- **A sold-out marker** becomes " - SOLD OUT", the way a scrape already writes a cancellation.
- **A free-entry note** is dropped from either end. The punctuation identifies it: without any, a title is
  saying the words rather than appending them, as "Free Entry Fridays" would, and keeps them.

A title whose whole content is one of these is left alone - it has said nothing else about the gig, and the
blank would reach `GigTitle` as a source that has stopped parsing.

`titleFrom` handles the spacing `GigTitle` refuses, where no better parse avoids it: as of 2026-08-21 that is
229, the Signature Brews, Blondies, Helgi's and Barfly through Dice, whose API hands a promoter's name back
verbatim ("LUN8 "); Alexandra Palace and Eventim Apollo, whose headings carry a narrow no-break space; and
OVO Arena and the AMG venues, whose APIs do the same. Jsoup's `text()` already normalises ASCII whitespace,
and a JSON name field has nothing else to read.

**It is deliberately blind to line breaks, tabs and control characters**, which no venue types and no API
returns: those are what a selector matching a card's container brings, so they must reach `GigTitle` and fail
rather than be tidied into a title that looks fine.

Three sources compose a title. **The Underworld** titles a gig with its running order where the heading gives
only the headliner in capitals; the event page's lineup is the bill as typed, cased, headliner last. It is
taken only when that last act is what the gig is listed as - a festival or club night is named for something
no act is - and only the headliner and three supports, a bill running longer (Cosmic Void Festival's is 39
acts) being a programme rather than a title. Doors and curfew are told from acts by carrying a clock time
where every act carries an en dash, not by position: Cosmic Void's page ends on its last act. **The Dev's
flyer** settles its slashes to " / ", a model reading a bill off a picture being inconsistent about the space
around them ("Liquified/Lobotomica" one run, "Liquified/ Lobotomica" the next).

**The Black Heart** appends the guests its promoters write under the headliner in the event copy, where its
listing heading names the headliner alone: as of 2026-08-25 that is 18 of the 51 gigs listed. Nothing in the
markup says which lines are the bill, so three things do. A line that is the marker and nothing else - "plus
guests", "plus special guests", "plus support", "with" - opens it, which is what keeps the seven
listings whose bill is still TBA saying nobody rather than billing an act called TBA. **"Featuring" is
deliberately not one of them**: every listing using it - Sticky Summer Swamp II, Heavy Halloween, Fill The
Void, the Civil War alldayer - is named for the night rather than for anything on it, so its bill is a
programme, exactly what the Underworld rule refuses when a running order tops out on something the gig is not
named for. And an act is told from what follows it by case: a bill is shouted, where the press quotes, ticket
lines, band biographies and bandcamp links written under one all carry lower case, so the bill ends at the
first line that does. A guest the heading has already named is dropped rather than repeated - A-Sun Amissa's
bills both of its supports twice. The headliner is taken as the copy spells it, falling back to the heading
only where the heading holds marks the copy dropped, neither side being reliably the one that kept them: the
heading writes "Handgemeng" where the copy writes "HÄNDGEMENG", and "AGROTÓXICO" where the copy writes
"AGROTOXICO". Taking the copy otherwise is what puts a whole title in one case - every guest billed here is
shouted, so a heading's "Greysight" beside "FRACTURE" would be the one part of its own title not written the
way the rest of it is. That makes it the reverse of the Underworld's, where the lineup is cased as the acts
case themselves and the heading is the one shouting. Of the 18 composed as of 2026-08-25, two take the
copy's spelling. The cap is the Underworld's, the
headliner and three supports, though the longest bill written under "plus guests" is two: it bounds a
mis-parse rather than the venue. It decorates the venue's own source rather than switching on inside the
Squarespace scraper the three venues share, the wording being these promoters' habit and not Squarespace's,
and reads the bill off the copy that scraper has already taken rather than going back to the page - no
second request, and no second copy of a selector. That rests on the Squarespace copy keeping its lines
(ADR 7): flattened, "WARPSTORMER BIRDWITCH" reads as one act exactly as "ISHTAR TERRA" does.

A cancellation is appended as ` - CANCELLED` where a source knows of one, today the Dice `status` field.

## Consequences

The title published is the one the classifier and the pairing saw, and the same every run. A venue's own
wording is lost where it was promotion rather than name. The one-spelling rule is load-bearing beyond
display: the sold-out marker matches what a cancellation uses, and the trailing-city pattern must look past a
cancellation suffix to find the city.

## Alternatives rejected

**Tidying at render time** - the prompt and the pairing both run before rendering. **Normalising a bill in a
comma-separated title** - the "+" there joins a list. **Matching "free entry" by wording alone** - "Free Entry
Fridays" is a name. **Letting `titleFrom` normalise all whitespace** - line breaks mark a wrong selector and
must fail. **Taking a whole running order** - thirty-nine acts is a programme.
 **Reading The Black Heart's bill off its
poster with a model**, as ADR 11 reads The Dev's - the copy under the poster already carries it, and a model
in the title path makes a title that re-words between runs a gig logged as changed every run. **Carrying the
bill as a field of its own** rather than in the title - it would leave the classifier's prompt and the
pairing reading the headliner alone, which is what they read today. **Matching the marker as a prefix** -
"Plus guests TBA" would bill an act called TBA. **An option on the shared Squarespace source** - The Dome
and The Fiddler's Elbow read the same template, and a promoter's wording is not the template's. **Fetching
the event page in the decorator** - the copy under it already holds the bill, once that copy keeps its
lines. **Preferring the copy's spelling outright** - it would publish AGROTOXICO for AGROTÓXICO.
