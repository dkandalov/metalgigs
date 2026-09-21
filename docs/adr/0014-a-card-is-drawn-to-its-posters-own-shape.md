# 14. A card is drawn to its poster's own shape, within bounds

Accepted. Recorded 2026-09-18, describing `GigCardView.cardRatio`, `imageRatios` and the `--card-ratio`
rule in `gigs.hbs`.

## Context

Every card was a square that `object-fit: cover` cropped whatever it held to. Nothing a venue publishes is
square by intention, so the crop was paid on 212 of the 359 images published on 2026-09-18, and the four
shapes it was paid on are clusters rather than a spread:

| Ratio | Images | Share | Where from |
| --- | --- | --- | --- |
| 1.00 | 140 | 39% | The Underworld, New Cross Inn, Cart & Horses, the Dice venues |
| 0.80 (4:5) | 56 | 16% | The Dome, The Black Heart, The Garage, Electric Ballroom |
| 1.78 (16:9) | 44 | 12% | the AMG rooms, OVO Arena, Islington Assembly Hall |
| 0.71 (A3/A4) | 31 | 9% | 229, The Black Heart, The Dev |
| 1.57, 1.50, 0.75 | 41 | 11% | Electric Brixton, Alexandra Palace, Scala, The Dev |
| everything else | 47 | 13% | a tail from 0.67 to 1.78 |

A square crop takes 42-44% off a 16:9 banner, and off its sides, which is where those venues write the band's
name - Spiritbox reads "tbox". It takes 20% off a 4:5 poster, and off the top and bottom, where the promoter
writes the date and the address. The card already prints the title, the venue and sits under the date, so
what that costs isn't the information: it is that a line of type is sliced through the middle, which reads as
broken rather than as cropped. Taking a Squarespace venue's poster from its own event page (ADR 9) made this
the common case rather than the exception, The Dome having 32 cards at 4:5 or narrower.

## Decision

The card takes its image's own ratio, bounded to 0.80-1.78 - 4:5 to 16:9, the narrowest and widest a venue
publishes in any number. The ratio is written per card as a `--card-ratio` custom property and `object-fit:
cover` still crops the few images outside the bounds to them. Measured over the same 359:

| Rule | Mean of each image lost | Untouched | Losing over 20% | Worst | Page height | Slack |
| --- | --- | --- | --- | --- | --- | --- |
| square, cover | 17.5% | 147 | 145 | 44% | 67,529px | 0 |
| **clamped 0.80-1.78** | **1.5%** | **305** | **0** | **17%** | **70,429px** | **11.5%** |
| clamped 0.80-1.00 | 11.4% | 211 | 89 | 44% | 72,114px | 11.5% |
| the image's own shape | 0% | 359 | 0 | 0 | 71,938px | 12.7% |

A published image's proportions are read with one `magick identify` call over the whole page, after the images
are published and before the html is rendered. It is `identify` rather than a webp header read because
ImageMagick is already required to publish at all, and one call rather than one per image because the page
publishes hundreds.

The cards in a row stay the same height, which is the grid's own default and is left alone: what varies is the
image inside the card, and the difference comes out as card-coloured space under the title rather than as a
gap in the grid. That is the whole reason a row can hold a 16:9 banner beside an A3 poster without the page
going ragged, and it is why the bounds matter - the wider the range a row can hold, the more of that space
there is.

## Consequences

The page is 4% taller and 11.5% of card area is background under a title - the cost that buys 305 posters
drawn whole. A card with a wide poster carries most of it, so the run of white space below a 16:9 banner is
the thing to judge by eye rather than by that number.

An image the render cannot measure has no ratio and its card falls back to square, which is what every card
was: a publish that failed leaves a card the same shape it would have had anyway. The ratio is written into
the markup, so a card now reserves its own height before the image loads rather than every card reserving a
square, and a lazily loaded poster no longer shifts the page as it arrives.

The bounds are the measurement above, so a venue that starts publishing something narrower than A3 or wider
than 16:9 is cropped to them without anything saying so - the same way a poster rule depends on a CDN's url
grammar (ADR 9). Nothing checks it.

## Alternatives rejected

**Letterboxing into the square** (`object-fit: contain`) - nothing cropped and the grid stays regular, but
17.5% of the average card is then flat background and a 16:9 banner is a strip across the middle of it.
**Padding to square at publish time**, over a blurred or flat-filled copy of the poster - the page needs no
change at all, but every image gets a border the venue didn't draw, and the fill is a guess about artwork.
**The image's own shape unbounded** - 12.7% slack against 11.5%, to spare 54 images a crop of at most 17%;
the bounds cost those images little and keep a row's cards from having to absorb the difference between an A3
poster and a panorama. **A ratio per row or per day, cropping every card in it to one shape** - it holds a row
level without any slack at all, but a day mixes shapes, so it only moves the crop rather than removing it:
measured per day it takes the mean from 17.5% to 13.8% while taking the worst case from 44% to 55%, and a row
is not something the render can know, the grid reflowing with the viewport.
**4:5 for every card** - right for the posters and 15% more page height, but then the 149 square images lose
20% and the banners lose more than they do today.
