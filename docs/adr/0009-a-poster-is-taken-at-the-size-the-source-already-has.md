# 9. A poster is taken at the largest size the listing already knows about, without another request

Accepted. Recorded 2026-08-25, describing the poster handling across `scrape/venues`.

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

## Alternatives rejected

**Fetching the original separately** - every one is recoverable from the url the listing gives. **Asking imgix
beyond the crop** - it caps rather than upscaling. **The O2's 480x281 crop** - render crops square and the wide
one letterboxes. **Giving up on a blank DHP card** - its own page often renders the poster, and is already
being fetched. **A `str` converter for OVO's `ImageURL`** - a boolean there fails the whole month.
