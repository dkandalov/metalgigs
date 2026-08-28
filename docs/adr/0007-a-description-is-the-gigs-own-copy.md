# 7. A description is the gig's own copy, scoped per venue, and cut by wording where markup will not do

Accepted. Recorded 2026-08-25, describing the `eventPageContent` of every source under `scrape/venues`.

## Context

Nothing renders a description; it exists to tell the classifier what kind of gig this is. That makes its
failure quiet - a description half the venue's nav still classifies, just worse, and nobody sees it. Taking a
whole page's text gets that every time: the page around the copy is the nav, footer, mailing-list form,
cookie notice, address, age policy, door times, prices, calendar links, and often a rail of *other gigs'
titles*. On a listing whose promoter wrote a sentence, that furniture is most of the text - and the same on
every page of that venue, which is what `ContaminationCheck` measures (ADR 3).

## Decision

Every source scopes its own `eventPageContent` to the block the promoter writes, the comment recording what
was in the way. Only a selector that matched nothing says `null`, which is the one thing a source can tell
about itself: `selectOrNull`'s cut returns `String`, so a match cannot be reported as nothing found. `""` is
a page that said nothing, or one that was all furniture this venue cuts.

| Venue | Taken | What was in the way |
| --- | --- | --- |
| Cart & Horses | `.page_header, .page_content_inner` | Useyourlocal scopes an event page to nothing, so opening times, address and socials reach every gig |
| Roundhouse | `.event-hero__heading-wrapper, section.event-about` less two blocks | the related-events carousel sits *inside* it, as does a card carrying 142 words of booking schedule, digital-ticket notice and restoration levy |
| Electric Brixton | `.event-context` | door times, price, age and ID policy, a mailing-list form, the footer |
| Windmill Brixton | `.EventDetailDescription` | the one block the promoter writes |
| The O2 | `.event_description` | likewise; every event page read had one |
| OVO Arena | `.event_description` | door times and ticket links above, then age policy, an AXS transfer notice, travel warnings about the stadium next door |
| Alexandra Palace | `.ap_text_block, #key-information` | `#event_content` also holds a sidebar of Buy Tickets / FAQs / Accessibility repeated on every page |
| Eventim Apollo | `.event-hero .variable-color.mt-sm` | the one hero element belonging to the gig; measured 82 to 1343 characters |
| Squarespace | `.eventitem-column-content` | `article.eventitem` holds the date twice over, the postal address, Calendar and ICS links - longer than some blurbs |
| DHP | `.single-article--contains-list .single-article__content` | the outer wrapper carries `.single-article` too, so selecting that doubles every word |
| The Underworld | `article.event` less its footer | the sitewide "other events" widget |
| Union Chapel | the article's children up to the first venue heading, plus the sidebar | see below |
| Scala | the lineup header box plus everything after "About &lt;artist&gt;" | see below |
| Dingwalls | `.elementor-location-single` | |
| Paper Dress Vintage | `.event__content` | |

Where markup cannot tell the difference, the cut is **by wording**:

- **The Underworld** and **Electric Ballroom** print a standard age policy as a sibling paragraph of the
  blurb, most of the text on a thin listing. Matched as typed - "This is a 14+ event" and "This event is a
  14+ event"; "Please note this show is 14+", "Strictly 18+ / physical photo ID required at entry", "Proof of
  age is required at entry".
- **Islington Assembly Hall** closes every listing with ticket terms and a £1.50 Venue Levy, about 470
  characters - the entire description where the promoter wrote nothing. They are sibling paragraphs in one
  wysiwyg block, the usual leading asterisk sometimes missing. Its "Support the Supports" campaign is a
  third, 403 characters word for word on 12 of 18 listings as of 2026-08-28 and half the text on the
  thinnest; the log dates its arrival, 45 of 74 gigs changing by that paragraph alone. The "Presented by
  &lt;promoter&gt;" line is kept: it names who booked the show, the one thing some listings say beyond
  boilerplate.
- **Union Chapel** has copy and venue sections as flat siblings, told apart only by each heading. Across six
  listings everything from "Book For A Pre-Show Dinner" on is the same ~1,850 characters of cafe, bar and
  access policy, longer than most gigs' copy. Remaining ticketing instructions sit *among* the copy, so they
  go line by line.
- **Scala** leads with ticketing and access furniture and closes with calendar links; the copy follows the
  "About" heading on every listing read. Its date line goes with the furniture - the date is already a field,
  and as prose only reads as a second date.
- **DHP** closes every listing with a "For more events" call to action.
- **Roundhouse** filters its url to `type=event`, dropping youth-programme courses that share 145 words of
  access and bursary copy embedded in each course's own text block - unscopeable.
- **Electric Ballroom** drops Bongo's Bingo, a club night rather than a gig, by the promoter's own ticket
  host on the card - the only cards linking there, three of the 106 events listed on 2026-08-28, one with an
  empty copy block. The title will not do it: the venue types Bongo's with U+2019, and each special is
  titled differently.

Three sources decode before the text is text: **New Cross Inn** renders client-side through Alpine.js, the
markup sitting in an `x-html` attribute as a JavaScript string literal whose every escape JSON has too, so
the JSON parser decodes it and the result is parsed for its text (the meta description is no substitute - it
is "Buy tickets for X live at Y" every time); **Squarespace** is handed an embed's fallback markup
html-escaped, so `text()` yields a visible `<a href=…>Grieve by Morag Tong</a>`, re-parsed to keep what the
link says and drop the tags; **AMG** serves the promoter's copy as HTML in the listing API.

**The Squarespace copy keeps its lines.** `text()` flattens every block boundary to a space, which serves the
classifier and loses what a bill's acts are told apart by: "WARPSTORMER BIRDWITCH" is two acts and "ISHTAR
TERRA" is one, and flattened they read alike. So the boundaries - block elements and `<br>` - are marked with
U+241E, a character no promoter types, before `text()` is called and cut into lines afterwards. Nothing else
about the text changes: the same characters in the same order, and blank lines dropped. It is what lets
`WithBilledGuests` (ADR 6) read The Black Heart's bill off the copy rather than fetching the event page a
second time and carrying a second copy of the Squarespace selector to read it with.

Where a source has a better description than the event page it takes it and saves the request: **Dice** takes
`rawDescription`, the venue's own copy, not the sibling `description` that appends Dice's footer ("Presented
by …", "This is an 18+ event"); **The Fiddler's Elbow** leaves event pages empty and puts everything in the
listing excerpt, the one description read off a listing, so its failure names that; **AMG** carries copy in
the listing call. **AMG** also synthesises one where there is none - 32 of the 334 events those venues listed
as of 2026-08-17 - from acts and genres, which is exactly what a description is for here, acts left in the
API's headliner-first order. A cancelled show has its copy replaced by AMG's standard notice, word for word
across venues, so that notice counts as no description: taken as copy it would read as one gig's text on
another. **The Dev** has no event page, so the title is the description - `""` would say a page was read and
said nothing, the one thing that never happened there.

## Consequences

Scoping is per venue and must be redone when a site is redesigned; `ContaminationCheck` notices, and only
surfaces it. Real content is deliberately dropped with the furniture in a few places, each measured as
boilerplate first. Cutting by wording is brittle where selectors are not, and silently: a venue that
rephrases a cut phrase keeps it, and one that adds a paragraph has it join every gig's copy until
`ContaminationCheck` says so, as Islington Assembly Hall's campaign did. A cut greedy enough to take
everything says `""`, which nothing tells from a page that was empty to start with. The Squarespace venues
log one run of changed gigs where the copy gains its line breaks.

## Alternatives rejected

**The whole page's text** - nav, footer, address, other gigs' titles. **Auto-stripping what
`ContaminationCheck` finds** - it risks eating real copy. **New Cross Inn's meta description** - the same
sentence every time. **Dice's `description`** - it appends Dice's footer. **Leaving an AMG event blank** - its
lineup and genres are what the classifier needs. **A hand-rolled unescaper** - JSON has every escape used.
**Flattening the Squarespace copy and reading a bill back out of it** - "WARPSTORMER BIRDWITCH" and "ISHTAR
TERRA" are the same two shouted words once the boundary is gone.
