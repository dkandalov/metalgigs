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
the same title. `WithTidiedTitles` decorates a `GigsSource` and makes eight changes. The first comes before the rest, which it lets read the title:

- **Letters styled with Unicode** - "𝐋𝐨𝐧𝐝𝐨𝐧 𝐌𝐞𝐭𝐚𝐥𝐅𝐞𝐬𝐭 𝟐𝟎𝟐𝟕", typed from the Mathematical Bold block the way
  social media fakes bold - become plain ones by NFKC normalisation, as do full-width, italic and circled
  forms, ligatures and superscripts. Nothing else reads them as the words: not the classifier's prompt, not
  the pairing, not the rules below, not a search of the page.

The other seven are each identified by punctuation rather than wording alone:

- **The trailing city** - "| London", optionally before a cancellation marker - is dropped.
- **A bill's separators** become " / ", spaced so a "+" written against a word is left alone. A title listing
  bands with commas is left entirely: "INHUMAN NATURE, PUPPY, AGNOSY + MORE" joins the last of a list rather
  than separating two acts, where a slash would read as a name.
- **A sold-out marker** becomes " - SOLD OUT", the way a scrape already writes a cancellation.
- **A cancellation marker** becomes that same " - CANCELLED": The Black Heart writes "- [cancelled]" and The
  Underworld "| CANCELLED", where a Dice status already writes the spelling they are normalised to. It runs
  before the trailing city, whose lookahead finds a city only past a cancellation written the one way.
  "POSTPONED" is left saying it - a gig put back may still happen, and folding it in would say otherwise.
- **A leading promoter** - "Sherwood Magazine presents:" - is dropped. Who put a gig on is not what it is,
  and is noise to both readers of a title. The colon is what identifies it, so "Jbm presents SMELLS LIKE
  NIRVANA" keeps its promoter: nothing there says where the name stops and the bill starts.
- **A free-entry note** is dropped from either end. The punctuation identifies it: without any, a title is
  saying the words rather than appending them, as "Free Entry Fridays" would, and keeps them.
- **An on-sale note** - Cart & Horses' "- ON SALE NOW‼️" - is dropped from the end, by the same punctuation
  rule. It is selling the gig, not naming it, and stops being true while the title keeps saying it. NFKC has
  written its "‼" as "!!" by then, so the emoji variation selector after it goes with it rather than being
  left trailing a plain exclamation mark.

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

Seven sources compose a title. **The Underworld** titles a gig with its running order where the heading gives
only the headliner in capitals; the event page's lineup is the bill as typed, cased, headliner last. It is
taken only when that last act is what the gig is listed as - a festival or club night is named for something
no act is - and only the headliner and three supports, a bill running longer (Cosmic Void Festival's is 39
acts) being a programme rather than a title. Doors and curfew are told from acts by carrying a clock time
where every act carries an en dash, not by position: Cosmic Void's page ends on its last act. **The Dev's
flyer** settles its slashes to " / ", a model reading a bill off a picture being inconsistent about the space
around them ("Liquified/Lobotomica" one run, "Liquified/ Lobotomica" the next).

**The Black Heart** appends the guests its promoters write under the headliner in the event copy, where its
listing heading names the headliner alone: as of 2026-08-25 that is 20 of the 51 gigs listed. Nothing in the
markup says which lines are the bill, so the copy's own shape does. A line that is the marker and nothing
else - "plus guests", "plus special guests", "plus support", "with" - opens it, which is what keeps the seven
listings whose bill is still TBA saying nobody rather than billing an act called TBA. Where no marker is
written the gig's own name opens it instead, the bill being the name and the acts under it: Morag Tong's copy
is "MORAG TONG / DRUIDESS / OUTBACK" and says so by nothing but the three lines. That anchor is what makes it
safe - a run has to open on what the gig is listed as, so the copy repeating a heading that is already the
whole bill (Gorge / Heretical Mutation / Insufferable) opens on a name the gig is not listed under and is
left, and a name followed by the band's story (Toseland's four paragraphs) has no run under it at all.
**"Featuring" is deliberately not one of them**: every listing using it - Sticky Summer Swamp II, Heavy
Halloween, Fill The Void, the Civil War alldayer - is named for the night rather than for anything on it, so
its bill is a programme, exactly what the Underworld rule refuses when a running order tops out on something
the gig is not named for. Where a shouted "FT." / "FEAT." / "FEATURING" stands on a line of its own inside a
run already opened on the gig's name, it is a join rather than an act: Dark Clouds Over London's copy is the
night, "FT.", then Hecate Enthroned, published as "DARK CLOUDS OVER LONDON ft. HECATE ENTHRONED" rather than
billing "FT." as a band or slashing the night and its act into what reads as two acts. A slot the copy has not
filled - "+ ONE MORE TBA" - ends the run rather than being billed. And an act is told from what follows it by case: a bill is shouted, where the press
quotes, ticket lines, band biographies and bandcamp links written under one all carry lower case, so the bill
ends at the first line that does. A guest the heading has already named is dropped rather than repeated -
A-Sun Amissa's bills both of its supports twice. The headliner is taken as the copy spells it, falling back
to the heading only where the heading holds marks the copy dropped, neither side being reliably the one that
kept them: the heading writes "Handgemeng" where the copy writes "HÄNDGEMENG", and "AGROTÓXICO" where the copy
writes "AGROTOXICO". Taking the copy otherwise is what puts a whole title in one case - every guest billed
here is shouted, so a heading's "Greysight" beside "FRACTURE" would be the one part of its own title not
written the way the rest of it is. That makes it the reverse of the Underworld's, where the lineup is cased
as the acts case themselves and the heading is the one shouting. Of the 20 composed as of 2026-08-25, two
take the copy's spelling. The cap is the Underworld's, the headliner and three supports, though the longest
bill written under "plus guests" is two: it bounds a mis-parse rather than the venue. It decorates the
venue's own source rather than switching on inside the Squarespace scraper the three venues share, the
wording being these promoters' habit and not Squarespace's, and reads the bill off the copy that scraper has
already taken rather than going back to the page - no second request, and no second copy of a selector. That
rests on the Squarespace copy keeping its lines (ADR 7): flattened, "WARPSTORMER BIRDWITCH" reads as one act
exactly as "ISHTAR TERRA" does.
**Alexandra Palace** appends the acts its event page's subheader bills, where its listing heading names the
headliner alone: 7 of the gigs listed on 2026-11-04, Trivium's being "Plus Special Guests: In Flames + Paleface
Swiss + VOLA". The same element holds straplines on other pages - "Emotional World Tour", "Directed by Martin
Murphy & Suzy Catliff", "A Camera Obscura at Alexandra Palace" - so wording is what tells them apart: a bill is
what follows "with" or "plus", either opening the subheader or after the name of a tour, "God Bless The Band
Tour - plus special guests The Lathums and The Guestlist". Acts are split on "+", "&", "and" and commas, so a
guest with "and" in its name would read as two acts; none listed on that date has one, and the page writes "The
Royston Club and Heidi Curtis" with nothing else to split on. A bill naming nobody yet, "with special guests
TBA", keeps the heading. The cap is the Underworld's. The bill comes from the event page already fetched for
the description, so there is no second request.

**The O2** does the same from each event page's tagline, through the same `titleBilledBy`, the two venues writing
one habit: 36 of the 151 gigs listed across the arena and indigo on 2026-11-04 name acts there, and others say
"Plus special guests" or "featuring Special Guests" and stop. "Special guest" marks a bill wherever in the tagline
it falls - "The Very Best of Change with Special Guests: Jazzie B (Soul II Soul) & Jaki Graham" - a "Plus" typed straight after it being dropped, as Five Finger Death Punch's "Plus
special guests Plus Lamb Of God and Bleed From Within" has it. A bare "with" or "plus" still has to open the
strapline or follow a tour's name, where "An Evening with..." would otherwise bill an act. A bill is published as
the page types it, so a run's per-night note stays in the title: "Olivia Rodrigo / Grace Ives (5 - 12 April) / Die
Spitz (14 April - 10 May)". Brackets can't simply be cut, Change's "Jazzie B (Soul II Soul)" being a name.

**The AMG venues** append the acts their listing api's `lineup` gives, headliner first: 122 of the 307 events
the four list on 2026-11-06 carry more than one, HEALTH's being HEALTH and VOWWS. No wording is read, the lineup
being a field, but the event's name sometimes bills the acts itself - "The Wonder Stuff + Inspiral Carpets",
"Boys Are Back Again: East 17 with special guest Blazin’ Squad" - so an act the name already contains is left
out. Containment is on letters and digits alone, the name typing that guest's apostrophe curly where the lineup
types it straight, and "The Clone Roses, Oasish, The Smiths Ltd" dropping the full stop the lineup gives "The
Smiths Ltd.". The cap is the Underworld's.

**New Cross Inn** reads the same from the `performer` list in each pit.live event page's JSON-LD: 46 of the 104
gigs listed on 2026-11-08 name more than one act there, Lagrimas's being Lagrimas, Hemiptera, Sevy Verna and
Clouded. The list is in no fixed order - Brakelight's opens on a support - so the gig's own name goes first and
the acts it doesn't already name follow in the order given. It is taken only where one of its acts is what the
gig is listed as, as the Underworld's running order is: Capital Death Fest, Trans Night Of Rage and Riffs
Against Fascism name none of their acts, and Reality Unfolds 2027 lists 23. Both lineups go through one
`titleWithLineup`, and none of the AMG events listed that day names a lineup its title doesn't contain an act
of. A misspelt performer keeps the venue's title: Los Fastidios' page lists "Los Fastidos".
A cancellation is appended as ` - CANCELLED` where a source knows of one, today the Dice `status` field.

## Consequences

The title published is the one the classifier and the pairing saw, and the same every run. A venue's own
wording is lost where it was promotion rather than name. The one-spelling rule is load-bearing beyond
display: the sold-out marker matches what a cancellation uses, and the trailing-city pattern must look past a
cancellation suffix to find the city.

A rule added here re-words what is published without relisting anything, because the decorator changes a gig's
title and never its id. That matters for The Dev, the one venue whose url is built out of its title (ADR 5):
its slug is fixed inside its own `latestGigs()`, before this runs, so a title tidied afterwards leaves the url
it was first logged under alone. The slug keeps a word the title has dropped, which is the price of the gig
staying one gig - the alternative, tidying before the url is built, republishes every affected night as a
second card.

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
