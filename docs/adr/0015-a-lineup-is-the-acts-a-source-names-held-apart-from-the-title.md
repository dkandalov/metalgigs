# 15. A lineup is the acts a source names, held apart from the title, and a joined name is cut only where two readings of a local model agree

Accepted. Recorded 2026-11-15, describing `Lineup`, `Bill.kt`, `JoinedActsReadByModel.kt` and
`DiceLineups.kt`. Amended the same day, twice: twelve more venues read a lineup off a heading or a line of
their copy, and sixteen ask what Dice lists. Amended 2026-11-26: New River Studios added, and "release" with
it - "Silica ‘Alone in Tow’ record release, plus Mercury, Spike, Kerosene" had read as four acts. Amended
2026-11-26: Oslo Hackney added, a seventeenth venue asked about on Dice and a 0.2MB recording of it; every
count of sixteen below is of the venues asked on 2026-11-15.

## Context

A title is one string, and by ADR 6 eight sources write a gig's bill into it. That serves the page and loses
what a bill is: a list. "Trivium / In Flames / Paleface Swiss / VOLA" cannot be told from a title a venue
typed with slashes in it, a fifth act is cut because a title takes four, and a suffix a venue wrote after the
headliner ends up in the middle. Four of those sources are handed the acts as a list and flatten it.

The other four read a bill a person typed, and there the list has to be made. "+", "/" and a comma only ever
separate acts. "&" and "and" do both jobs: of the 36 such joins on the bills of Alexandra Palace, The O2, the
AltTickets venues and the eleven other venues sampled with them on 2026-11-15, 27 joined two acts and 9 were
inside one act's name - Beaux Gris Gris & The Magic Three, Ruby Doomsday and The Mink, Coheed and Cambria.
Nothing in the wording separates them: "The" after the joiner was one band six times and two acts four.

## Decision

**A gig carries a `Lineup`: the acts its source names, in the order it names them.** It is observed like the
title and the description, so a support announced later is a gig logged as changed. It is empty where a source
names nobody, and the log writes no key for an empty one - a line from before lineups
reads back as naming no acts and is rewritten byte for byte, which the compaction check depends on.

**`Gig` takes no default for it.** A source names its lineup or writes `Lineup.none`, so a scraper cannot be
added without the question being answered - Dice's `artists` sat in a response the scrape already parsed, for
seven venues, because nothing asked.

**The title is unchanged.** Every source still composes the title ADR 6 describes, and nothing reads a lineup
yet: this records what a source knew, ahead of the page being built from it.

A lineup is whatever the source names and no more. Where the source gives a list, that is the whole bill,
headliner included. Where it is read off a line under the heading, it is the supports alone: the heading may
be "NFD - The 25th Anniversary Show!" as readily as a band, and nothing says which.

| Source | Venues | The lineup is |
| --- | --- | --- |
| AMG | 4 | the api's `lineup`, headliner first |
| Dice | 7 | the api's `artists`, which the scrape had fetched and ignored |
| New Cross Inn | 1 | the `performer` list in pit.live's JSON-LD |
| The Underworld | 1 | the running order, headliner first, every act of it |
| The Black Heart | 1 | the lines its copy bills (ADR 6), every one rather than a title's three |
| Alexandra Palace, The O2, AltTickets | 5 | the acts the line under the heading bills |
| Scala, OVO Arena | 2 | the same: Scala's subtitle in its header box, and the tagline of a page that is The O2's template |
| Union Chapel | 1 | the subheadings a promoter types under the gig's name, read as one line |
| Islington Assembly Hall | 1 | a paragraph of the copy that is a bill and nothing else |
| The Dome | 1 | the supports its copy's opening formula names |
| Cart & Horses, Paper Dress Vintage, Nambucca, The Dev | 4 | the heading, where it bills two acts or more |
| Windmill Brixton, The Lexington, New River Studios | 3 | the same, their headings listing acts with commas |
| Dingwalls | 1 | the heading where it bills, and otherwise a paragraph of the copy that does |

What a Black Heart bill opens on is left out where it is not an act: a night its acts are billed as featured
at, or a heading that names the acts itself.

Eventim Apollo, Bush Hall and the Roundhouse name a support only in a sentence of their copy - "Alex Spencer
will also join as support" - and Electric Brixton, Electric Ballroom and The Fiddler's Elbow not at all, so those
six say `Lineup.none` for themselves. Reading an act out of prose is a use of the model nothing here has
measured.

**A heading is a lineup only where it is a bill, and then whole or not at all.** It is cut from the title as
ADR 6 tidies it - the promoter and the sold-out marker already gone, "+" already a slash - and it has to come
apart into two pieces or more. One name on its own may be a band or a night: "Saxon" and "December 10" were
both listed at Eventim Apollo on 2026-11-15, and nothing in the wording says which. A piece that is more than a
name - it holds a colon, a spaced dash, "tour", "presents", "showcase", "launch", "release" - leaves the gig with no lineup
rather than a wrong act in it: "Club Orbit: Ro Nova + Vanity Fairy" and "Dayseeker: Creature In The Black Night
Tour" put the act on opposite sides of the same colon. A note in brackets, a guest's label and a framing emoji
come off a name; "+ special guests" and "+ TBA" name nobody and are dropped, which can leave a lineup of one.
Commas cut a heading only for the two venues that list acts that way, since elsewhere a comma is as likely in a
night's name. Of the 167 gigs the five heading venues other than The Dev listed that day, 52 got a lineup; The Lexington's "acts -
night's name" headings are most of what did not.

**A bill in the copy has to say what it is.** A line under a heading need only open on "plus"; a paragraph of
copy that does may be "plus loads of other well-known F Mac classics". So there the line has to name guests or
support and be short, and at The Dome it has to be the venue's opening formula - "Action! presents URNE with
special guests The Grey and Moon Reaper live at The Dome", on 49 of its 55 gigs - rather than a sentence further
down that happens to mention a guest. A bill that says supports are coming and names none - 17 of those 55 -
names nobody.

**A written bill is cut on "+", "/" and commas, and a piece holding "&" or "and" is cut only where two readings
of a local model agree.** `gemma4:26b`, the model that reads The Dev's flyer (ADR 11), is asked twice about the
piece. The first reading lists the acts and is told to separate names only where it recognises each side as an
act in its own right. The second is a different question about the same piece - one act, two or more, or
unsure - and the piece is cut only if it answers two or more. The first reading is only ever a way of cutting:
each act it names has to be in the bill as written, in order, with nothing between two of them but the joiner
or a note in brackets, so a reply that rewords a name or adds one cuts nothing.

Measured on 2026-11-15 against 69 single acts whose names hold "&" or "and", off the Dice and AMG lineups of
some forty London venues, and 26 pairs off venues' own bills (`JoinedActsExperiment`, `joined-acts.tsv`):

| Reading | Single acts cut, of 69 | Pairs cut, of 26 |
| --- | --- | --- |
| a plain prompt | 31 | 26 |
| the first reading alone | 5 | 26 |
| the second alone | 1 | 20 |
| both agreeing | 0 | 20 |

The two fail on different names, which is the whole of why it works: the first cut Ruby Doomsday and The Mink
and four more, the second called Fabio & Grooverider two acts, and no name fooled both. The six pairs left
joined are acts the model does not know - Odeal and Waseel, Guilt Trip and Bodyweb - and a name left joined is
still what the venue wrote.

**Where a bill shouts its names, the case of an "and" is read before the model is asked.** A promoter who
types acts in capitals types the word between two of them in lower case, and leaves an act's own "AND" shouted
with the rest of its name. In the bills and copy read off some thirty venues on 2026-11-15, a lower-case "and" between
shouted names joined two acts all nine times - "WINTERFYLLETH and OPIA", "KATATONIA and MY DYING BRIDE" - and a
shouted one was a word in a single act all eight - "AX AND THE HATCHETMAN", "CHRISTINE AND THE QUEENS", "HUE AND
CRY". Union Chapel's "ROME and OF THE WAND AND THE MOON" holds both. So such a piece is cut on the lower-case
"and" without a call, and a shouted "AND" in what that leaves is not asked about. It applies only where every
name in the piece is shouted: "The Grey and Moon Reaper" says nothing by its case, and an ampersand has none.

**Sixteen venues are also asked about on Dice, which sells their tickets and lists each gig's artists apart**,
whatever site the gig itself is read off. It is the answer for most of what a typed bill cannot give: a heading
of one name is confirmed as an act by being listed as one, and a pair the model does not know - "The Cases and
Fletchr Fletchr" at Scala - arrives as two entries. On 2026-11-15 those venues' own sources gave 120 of 822 gigs
a lineup, and 482 had one after Dice: Islington Assembly Hall went from 4 of 61 to 58, Union Chapel from 3 of 117
to 58, Scala from 9 of 47 to 44, and Electric Brixton, the Roundhouse and Bush Hall from none.

A gig is matched to the event on its night that goes under its name, or that bills an act its heading names. The
night alone will not do - The Dome and Downstairs at The Dome are one venue to Dice - and a name under four
letters, which is inside too many titles to say anything, matches only as the whole of a heading. An early and a
late show can share a night and a name, and the fuller bill of the two is taken.

Of the 452 gigs Dice listed artists for that day, 24 had a heading
that does not hold the first artist listed; read one by one, they were festival nights, a name spelt two ways
("Saetaier", "Sætaɪər"), and one night at The Grace where the two sites bill different acts. Dice lists a pair
beside each of its two - "Ben Watt", "Tracey Thorn", "Ben Watt and Tracey Thorn" - and the pair is dropped.

**Dice's lineup is kept where it names two acts or more, or where the venue's own source named nobody**;
otherwise the venue's own stands (`preferred`). One act on Dice is the gig's own name, which a typed line of
supports says more than. Where Dice cannot be read the venue keeps its own lineups and the run says so: failing
the scrape, as an unanswering model does, would hang sixteen listings on a ticket seller being up.

**A source asks Dice itself, with the client it reads its own listing through**, and ends on
`withLineupsOnDiceAt("Scala", client)`. What Dice calls a venue is a fact about that venue, and asking is one
more request the venue's scrape makes, so neither is anything `Main` or a collaborator need know: a source is
given a client and, where it reads a bill, a `JoinedActs`, as before. `DicePartnerApi` is the paging, the key and
the json, shared with the seven venues read off Dice outright, and knows nothing of a gig; the matching is a
function of a gig and a list of events.

**Both replies are held to a JSON schema**, a list of strings and one of three words, which is what
`Chat.Ollama` passes as ollama's `format`. That is not a convenience: asked the same questions with the reply
left as text, the second reading said "two" far more readily and three single acts were cut.

A run asks about a piece once, so every night of a run of nights gets one answer. A model that cannot be asked
fails the venue's scrape, as it does The Dev's (ADR 2): a bill left joined because ollama was down, and cut on
the next run, would be the same gig logged as changed twice over.

## Consequences

Sixteen more venues' scrapes need ollama running, where The Dev's did, though only for a bill that joins names this
way - a few dozen pieces a day, two calls each.

The first scrape after this logs every gig with a lineup as changed, once.

Nothing stores a reading. The same piece is asked about on every scrape, and a reading that differed between
runs would log the gig as changed each time, as The Dev's titles did. Two runs of both readings over the 95
names agreed on every one, which is the only evidence it will not.

A lineup is not one thing across sources - a bill with its headliner from an api or a heading, supports alone
from a typed line - and whatever composes a title from it has to allow for a headliner the title already names.

A lineup read off a heading says nothing the title does not; what it adds is the acts apart. And the rules that
read one are fitted to how these venues typed on one day: a heading that is a bill by some marker not listed
here gets no lineup, quietly.

A pair the model does not know stays one entry unless its case tells it apart. The Dome's "The Grey and Moon
Reaper" is in its lineup as a single act, which it is not; it is still what the venue wrote.

Sixteen venues are read from two sites. A lineup can differ between a run where Dice answered and one where
it did not, which logs the gig as changed twice; and it leans further on the one public key (ADR 8).

Each of those venues' tests holds a recording of what Dice listed, 3.9MB across the sixteen, and a test that
stands a few lines of markup in for a venue's site answers Dice with the same markup: the source reads that as
Dice not answering, says so, and keeps its own lineups.

Keeping Dice's bill whole can lose an act the venue typed: The Black Heart's copy billed The Mechanist, Dacara
and Night Thieves where Dice listed the first two. It gains one as often - Winter Hotel's third act was on Dice
alone - and merging the two would bill "The Grey and Moon Reaper" beside The Grey and Moon Reaper.

Cart & Horses, Nambucca, OVO Arena, The Dev and The Fiddler's Elbow are not on Dice, or barely.

## Alternatives rejected

**Wrapping a source in `Main` to ask Dice** - the venue's name on Dice then sits in the wiring, apart from
everything else known about the venue. **One collaborator answering both for a joined name and for Dice**
(`ActsKnownElsewhere`, built and then replaced) - it kept Dice out of sixteen tests, and for that put an
interface, a cache and a stand-in between a source and a request it could make with the client it already had.
**The Dice gig source inside each venue's source** - it reads a gig's url off a redirect, a request for every
event to make an id nothing here keeps, and fails as a whole where one of them does. **Matching a gig to Dice by its night
alone** - two rooms share one. **Merging the two lineups** - see above. **Failing a venue where Dice cannot be
read** - see above.

**Cutting on "&" and "and" always** bills bands that do not exist: three of the 35 titles composed for the
AltTickets venues on 2026-11-08. **Never cutting** leaves 27 real pairs in 36 joined, and is what a title still
does. **A rule on the bill's label** - cut after "special guests", not after "Support:" - got 21 of the 27 and
cut Coheed and Cambria. **The model alone**, on a plain prompt, cut 31 of 69 single acts: it knows Coheed and
Cambria and does not know the small bands these venues book. **`qwen3`**, the other model on this machine, cut
four of nine and billed a strapline's directors as acts. **Leaving the bill joined where the model cannot be
asked** - see above. **Reading the bill into the description** - most descriptions are flattened to one line,
a list written into text has to be cut again, and a gig carried over from the Garage's old site keeps its old
description (ADR 14).
