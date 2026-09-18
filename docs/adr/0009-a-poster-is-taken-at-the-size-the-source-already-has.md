# 9. A poster is taken at the largest size the listing already knows about, without another request

Accepted. Recorded 2026-08-25, describing the poster handling across `scrape/venues`. Amended 2026-09-18: a
Squarespace venue's poster is the image block in the gig's own copy, its card's thumbnail being the venue's
featured image rather than the night's artwork.

## Context

Posters render at 768px, and a listing's thumbnail is sized for its own cards: 200px at The Underworld,
550x300 at Paper Dress Vintage, 600px at Windmill Brixton, 650px at Alexandra Palace, 750x450 at Islington
Assembly Hall. Rendering those means enlarging a thumbnail, while behind each the site holds something
bigger and the listing already says enough to ask for it. Separately, a poster is where a broken selector
hides best: it matches something, the same something on every card, and no check reading a gig's text would
notice (ADR 3).

## Decision

The full-size image is recovered from what the listing already carries, with no extra request.

| Venue | Recovered by | Measured |
| --- | --- | --- |
| The Underworld | dropping the imgix `w=` | `w=200` gives 200px, dropping it the full 1667px; asking beyond caps rather than upscales |
| Windmill Brixton | dropping Music Glue's `mode=`/`width=` | 600x750 with them, 1080x1350 without |
| Paper Dress Vintage | stripping `-lbox-<W>x<H>-FFF` | originals 800px-2560px across a sample |
| Islington Assembly Hall | stripping `-<W>x<H>-c-center` | uploads behind the 750x450 crops ran to 2560x1536 |
| Alexandra Palace | the widest `srcset` entry | `src` is 650px, `srcset` carries the same image to 2048px |
| The O2 | the square crop, not the 480x281 | the square is at least 564px and survives render's own square crop |
| Eventim Apollo | nothing | the card's url already asks the CDN for 768 square |
| Dice | nothing | same imgix CDN, linked with no `w` at all |

Where a card holds more than one image, which is the gig's is stated: **Electric Brixton** takes the
thumbnail from its own container, `.event-image` also holding an empty `img` for the rollover animation;
**Windmill Brixton** takes the first `img`, the second being a backup the theme swaps in on failure;
**Eventim Apollo** takes the first `<picture>`, the narrow-breakpoint one being a generic house image on some
listings.

Where a poster is not an `img`: a CSS `background-image` at Union Chapel, Scala, Electric Ballroom and Paper
Dress Vintage; a lazy-loaded `data-src` on an anchor's background at Islington Assembly Hall, with no `src`
until the theme's JavaScript runs; `data-image` at Squarespace venues, whose Events List block resolves `src`
eagerly on some sites and not others.

Where a listing's picture is not the gig's, the event page's is taken instead. **The Black Heart** and **The
Dome** hang a Squarespace card off whatever the venue set as that event's featured image, which is as often a
band's press shot or a landscape crop as the night's artwork, while the poster is the first image block in the
copy below it - on a page already fetched for the description (ADR 7), so still no request of its own. The
card's thumbnail is the fallback, and only when the page carries no image at all. Measured on 2026-09-18:

| Venue | Listed | With an image block | A different picture from the card | The same picture at a new url |
| --- | --- | --- | --- | --- |
| The Dome | 67 | 67 | 64 | 3 |
| The Black Heart | 47 | 46 | 25 | 21 |

The same upload reaches the two places by different paths - the card's dated one, the block's uuid one - and
escapes a space as `%2B` in one and `+` in the other, which is why a picture that doesn't change still changes
url. The one Black Heart gig with no image block keeps its card's thumbnail; a second, whose card carried no
thumbnail at all, is published under its page's poster where before it would have failed that whole listing
(ADR 2).

Where a listing has no poster, the source asks the next thing that might:

- **AMG** serves `image: ""` for an event with no artwork while the page still renders AMG's shared default.
  The hero is the page's only full-bleed image (`sizes=100vw`), a CDN resize whose query string is dropped to
  recover the bare asset. The page url is built from the encoded name and first act's id; a listing's
  `encodedName` can lag the canonical slug, answered with a 308 the client follows.
- **DHP** asks four places in order - the card's `data-lazy-src`, its `src`, the event page's hero, then the
  venue's house image. A blank card is not the last word: the listing can print "Image not found" while the
  gig's own page renders it, and that page is fetched for the description anyway. The guide walk reaches gigs
  announced before artwork exists, which have none of the three, so The Garage stands in its own crowd shot -
  published showing the room rather than dropped or failing the listing. The Grace has no such image and still
  fails.
- **Bush Hall** is the one venue whose listing has nothing bigger behind it: every card image measures
  154x154, whether See Tickets names it by uuid or writes the size into the file name, and no larger variant
  answers - dropping a `-154x154` suffix or asking for `-300x300`, `-768x768` or `-1000x1000` all 404. Two of
  the 56 cards listed on 2026-09-05 carry no image at all. The event page's `og:image` is a different asset at
  300x300, present on every page read, and that page is already being fetched for the copy. 300 is still under
  the 768 render targets, but `RESIZE_GEOMETRY`'s `>` never enlarges, so it is the difference between a card
  drawn at 300px and one drawn at 154px behind a 260px slot.
- **OVO Arena** carries `"ImageURL": false` - not a url, null, or an absent field. Kondor reads a missing and
  a null field as absent, but a boolean where a string belongs fails the whole month's parse rather than the
  one event, so it is read as a node: anything but a string is no poster.

Everything else goes through `posterUrlFrom`, which fails naming the gig (ADR 2): an unmatched selector and an
empty API field both arrive as `""`, and `PosterUrl`'s own message has no gig to name.

## Consequences

Render sizes the image down for the card instead of enlarging a thumbnail, at no extra request per gig. Each
recovery depends on a CDN's url grammar, so a site changing its image pipeline silently returns a thumbnail
again, which nothing checks. A DHP gig can be published under the venue's crowd shot rather than its artwork,
a deliberate trade against dropping it. `SharedPosterCheck` (ADR 3) is the counterweight: the only check
reading what is shown with a gig rather than what it says.

A published image is named from a hash of its poster url (`ImageCache.kt`), so moving a venue to a different
picture re-downloads and re-encodes every one of that venue's gigs once, including the ones whose picture
hasn't changed, and prunes what they were published under. Taking a Squarespace poster from the copy also puts
it behind the same selector the description reads, so a venue restyling its event page loses both at once
rather than one of them.

## Alternatives rejected

**Fetching the original separately** - every one is recoverable from the url the listing gives, Bush Hall
excepted, where the bigger one comes off a page already being fetched rather than out of a request of its own. **Asking imgix
beyond the crop** - it caps rather than upscaling. **The O2's 480x281 crop** - render crops square and the wide
one letterboxes. **Giving up on a blank DHP card** - its own page often renders the poster, and is already
being fetched. **A `str` converter for OVO's `ImageURL`** - a boolean there fails the whole month. **A
Squarespace event page's `og:image`** - Squarespace builds it from the same featured image the card carries,
so it is the press shot again, at a `?format=1500w` crop of it.
