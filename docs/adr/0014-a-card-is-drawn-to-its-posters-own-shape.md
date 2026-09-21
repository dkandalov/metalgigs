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

| Rule | Mean of each image lost | Untouched | Losing over 20% | Worst | Page height |
| --- | --- | --- | --- | --- | --- |
| square, cover | 17.5% | 147 | 145 | 44% | 67,529px |
| clamped 0.80-1.78, cards left ragged | 1.5% | 305 | 0 | 17% | 70,429px |
| clamped 0.80-1.00, cards left ragged | 11.4% | 211 | 89 | 44% | 72,114px |
| the image's own shape, cards left ragged | 0% | 359 | 0 | 0 | 71,938px |

Those are the figures for the bounds alone, against a grid whose cards are each only as tall as their own
poster. A row of cards has to be level, so the figures the page actually gets are in the consequences below.

A published image's proportions are read with one `magick identify` call over the whole page, after the images
are published and before the html is rendered. It is `identify` rather than a webp header read because
ImageMagick is already required to publish at all, and one call rather than one per image because the page
publishes hundreds.

The cards in a row stay the same height, which is the grid's own default and is left alone. A row is as tall as
its tallest card, and the two ways of giving that height to a shorter card's poster both cost more than they
save: left as card background it was 198px of it under a 145px banner sharing a row with two A3 posters, and
grown into by the poster it took the banner back to the crop this decision exists to avoid, 51% at OVO Arena
against the 44% a square card cost.

So the poster is drawn at the size its own ratio asks for, `margin-block: auto` splitting the spare height
evenly above and below it, and that space is filled with the poster again - a second `img` of the same src,
blurred and darkened behind the first. Same url, so the browser fetches it once and lazy-loads it the same
way; its own decode is what it costs. `max-height` bounds the other direction, a poster taller than its row
still being cropped to the row.

## Consequences

Over the 339 images published on 2026-09-21, at four columns: 292 are drawn whole, and the 47 that are cropped
are the A3 posters pulled up to the 0.80 bound, losing 9.9% on average and 17% at worst. Across every image it
is 1.4% against the 17.5% a square card cost. 162 cards show the fill, 87px of it on average and 215px at the
deepest.

What that spends is vertical space - the page is 4% taller - and a second decode of 162 posters. It also means
a card can be mostly fill: a 16:9 banner in a row an A3 poster has set the height of is a strip of poster
across the middle of a blurred copy of itself, which is the shape to judge by eye rather than by the numbers
above.

Nothing about this is fixed at render time except the ratio, so the same gig's card changes shape between
renders as the gigs around it change.

An image the render cannot measure has no ratio and its card falls back to square, which is what every card
was: a publish that failed leaves a card the same shape it would have had anyway. The ratio is written into
the markup, so a card now reserves its own height before the image loads rather than every card reserving a
square, and a lazily loaded poster no longer shifts the page as it arrives.

The bounds are the measurement above, so a venue that starts publishing something narrower than A3 or wider
than 16:9 is cropped to them without anything saying so - the same way a poster rule depends on a CDN's url
grammar (ADR 9). Nothing checks it.

## Alternatives rejected

**Letterboxing into the square** (`object-fit: contain`) - nothing cropped and the grid stays regular, but
every card is then a square whatever it holds, where this fills only the height a row actually forces.
**Padding to square at publish time**, over a blurred or flat-filled copy of the poster - the same picture,
but baked into the file, so the border is fixed at the ratio that render guessed and the bytes carry it for
every later page; done in the browser it costs no pipeline step and no image that can't be reused.
**The image's own shape unbounded** - 12.7% slack against 11.5%, to spare 54 images a crop of at most 17%;
the bounds cost those images little and keep a row's cards from having to absorb the difference between an A3
poster and a panorama. **A ratio per row or per day, cropping every card in it to one shape** - it holds a row
level without any slack at all, but a day mixes shapes, so it only moves the crop rather than removing it:
measured per day it takes the mean from 17.5% to 13.8% while taking the worst case from 44% to 55%, and a row
is not something the render can know, the grid reflowing with the viewport.
**4:5 for every card** - right for the posters and 15% more page height, but then the 149 square images lose
20% and the banners lose more than they do today.
