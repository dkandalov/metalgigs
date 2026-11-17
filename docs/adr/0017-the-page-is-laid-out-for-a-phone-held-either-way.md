# 17. The page is laid out for a phone held either way, and its header stays one row

Accepted. Recorded 2026-11-17, describing the stylesheet in `gigs.hbs`, from the comments that had grown on it.

## Context

The page is a header - its title, a search field and an about button - over a grid of cards, each a square
poster above a title and a venue, the whole card one link. On a desktop that lays itself out: the grid fills
with 220px columns and the search sits beside the title. On a phone each part of it did something unwanted,
upright or on its side, and each rule below answers one of those.

## Decision

**Beside the title, the search is a control and not a banner.** It grows into whatever the title leaves, up to
16rem, a width that keeps it a control in the header rather than a second banner across it. On a narrow screen
that is not a phone it drops onto its own line rather than squeezing the title.

**Under 30rem the header stays one row.** The field is collapsed to a button, a square matching the about button
so the two read as a pair. The heading gives up some size to make room; at 2rem it very nearly fills a phone's
width on its own. The buttons keep the row and the title is what gives way, since they are a fixed size and it
isn't. Flex breaks to a new line before it shrinks anything, so without `nowrap` the buttons drop to a row of
their own the moment the title's natural width plus theirs exceeds the screen - which on a phone it does.
`min-width: 0` lets the title shrink past its own content, wrapping to a second line on the narrowest screens
rather than pushing the buttons off the row.

**Searching takes over the row it is already on rather than adding one below it.** The title and the about
button give way to the field, so tapping search doesn't shove the listing down the page just as the keyboard
arrives, and the field gets the whole width instead of what's left beside a button it has nothing to do with.
The 16rem cap goes: it keeps the field a control beside a title on a desktop, and here the field is the row. It
is held open by a query as well as by focus, or blurring would hide what was typed (ADR 16).

**Under 32rem the grid is two columns.** One column leaves a single gig filling most of a phone screen. The
breakpoint is just above the width at which the grid fits two 220px columns unaided, so nothing is left between
them where the count drops back to one.

**A phone on its side gets the poster beside the text.** A landscape phone is too short for a square poster with
the text under it - the card comes out 346px tall on a 375px screen. It is keyed on height, under 30rem, because
every desktop monitor is landscape too, and placed after the two-column block because both match a viewport
that is short and narrow. The image stretches to whatever height the title wraps to, so it needs a floor to
stay a poster rather than a sliver beside a one-line title, and it has to give up the square ratio the upright
layout sets.

**A title breaks at its slashes.** Band names run together without spaces - Man/Woman/Chainsaw - and no browser
breaks at a slash, so the title carries a `<wbr>` after each such one. `overflow-wrap: anywhere` is for a long
name with nothing to break at: it goes mid-word only where there is no `<wbr>` to use instead, and it shrinks
the title's min-content width, which is what the grid measures its columns against.

**The focus ring is two-tone, drawn over the card and inside it.** Two-tone, because the link wraps the poster
and one colour is only guaranteed to show against some artwork. Drawn as an overlay rather than as the link's
own outline and inset shadow: an inset shadow paints below child content, so the dark half of the ring would sit
behind the very image it exists to stand out against. Inside the card rather than around it, because
`.gig-card` clips whatever leaves its rounded box. The transparent outline is what leaves a focus ring in
forced-colors mode, where shadows aren't painted at all.

## Consequences

The three blocks are read in order: the landscape one overrides the two-column one on a viewport both match, and
moving it above stops it doing so.

The 32rem breakpoint is tied to the grid's 220px minimum, and the landscape image's floor to its width. Changing
one of a pair without the other reopens the gap each was set to close, and nothing checks either.

The one size measured here is the 346px card on a 375px screen.

## Alternatives rejected

**A search row under the header on a phone** - it pushes the listing down as the keyboard comes up. **Letting
the header wrap on a phone** - the buttons take a row of their own. **One column on a phone** - a gig a screen.
**Keying the sideways layout on orientation** - every desktop is landscape. **The link's own outline, or an
inset shadow, for focus** - one colour is lost against some posters and an inset shadow paints under the image.
**A ring around the card** - the card clips it.
