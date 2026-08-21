---
name: code-smells
description: Check a change against the shapes this project rejects, and say so without reverting on your own. Use before writing a comment, before reporting a change complete, when reviewing a diff, and when a fix you are about to write would introduce one of these shapes.
---

A smell is a shape, not a rule. Name it, say what it costs, and keep building - whether it is worth
taking is the user's call, and some of these were taken deliberately. Don't quietly restructure to
avoid one already accepted, and don't present the smelly shape as clean when you pick it anyway.

## False and derivable comments

The default is no comment: prose rots while the code keeps working. One earns its place by being
true - verified against the code - and not derivable from the declaration, the adjacent code, or a
well-known pattern. Delete a weak one rather than rewording it, and touch only those you are editing.

Cut on sight: change history, renames, rejected alternatives (which move to an ADR rather than being
lost - below), restatements of the declaration, which caller populates a field, explanations of
familiar patterns. Keep what reading the code cannot recover: site behaviour, measurements, library
traps (Ovo Arena's `"ImageURL": false`, which Kondor would fail the whole month's parse over),
distinctions between similar things (`logicalDate` versus `recordedAt`). Wording: sentence
capitalisation ending in a full stop, an identifier keeping its case.

Over three lines, or the same reasoning at a second site, and it is a decision being explained rather
than a fact recorded - write `docs/adr/`. ADR-1 was five such comments across three files, a piece of
one decision each, none able to hold what the list above cuts. What stands at each is a label naming
the question, seven words before the path, on the declaration the decision is about: `class GigsLog`
carries `// Why every state is a projection: docs/adr/0001-the-log-is-append-only.md`, where the prose
had grown on `compact` and `seq`. Length alone isn't the smell - `MAX_TITLE_LENGTH`'s measured bounds
record something nobody decided, and have no ADR to go in. Nothing checks the path, so grep `docs/adr/`
before renaming one.

## A parameter a function takes only for its error message

`posterUrlFrom` takes `gigUrl` for nothing but the string inside its `check`; `descriptionFrom` takes
`url` the same way. Attribute the failure where the identity already lives - the call site building
the object - rather than threading it into a leaf. Both stand deliberately: naming the gig turns an
unactionable line in the daily log into a fix. Consistency with them doesn't justify a third.

## A value built only to be rejected a call later

`imageUrl` in OvoArenaGigsSource ends `.orEmpty()`, `squarespaceThumbnailUrl` and `widestImageUrl`
fall through to `""`, and all three hand it to `posterUrlFrom`, which rejects blank; `AmgEvent`'s
`description` writes `?: ""` only for the `takeUnless { it.isBlank() }` beneath it. Let an absent
value stay absent - `String?` to the boundary that decides - so the type refusing it is the only
thing that has to know.

## An invariant checked further down than the type that could refuse it

`GigDescription` takes a blank string that `MisshapenGigsCheck` calls "no description" a pipeline
later, so every step in between reads as though a gig with no description happens. Refuse it in the
type, as `GigTitle` and `PosterUrl` do, weighing what that costs: a type refusing fails the venue's
whole listing for the run, where a check withholds the one gig and names it. A bound that was measured
rather than required, like `MAX_TITLE_LENGTH`, isn't this shape.

## Implementation details before the declaration they serve

Declare high-level interfaces, classes, functions and properties before what implements them, so
every reference points back towards the top of the file
(https://dmitrykandalov.com/tidy-kotlin#high-level-declarations-first). Every file in src/main/kotlin
reads that way - GigsSource.kt opens with the interface, Main.kt with `main` - and each source class
puts `venue` and `latestGigs` above the url and patterns they use. Venue.kt is the one deviation and
says why: top-level properties initialise in file order, so `venuesById` can only follow `allVenues`.

## A file past 500 lines

Past 500 lines a file has stopped being one thing, and what it splits into says what it was
accumulating. GigsSource.kt carried the interface, the shared helpers and 20 venue scrapers at 1030
lines; it is 40 now, the scrapers having moved to scrape/venues/ - one file per venue, except where
venues share a scraper, as The Black Heart, The Dome and The Fiddler's Elbow share
SquarespaceEventsGigsSource. GigsSourceTest.kt went the same way from 1831 lines, one test file per
source file, with assertScrapesGigs, noHttp and pageOf left behind in GigsSourceFixtures.kt. Nothing
in src is above 447 lines now, so this entry names what was split rather than anything still over.

## A declaration kept in a file that isn't about it

Is this what the file is about, and does anything else call it? No to both and it belongs where it's
used, private - which is the point. `slug` sat in ImageCache.kt for one call in `posterGigUrl`, and
`fetchImageContent` and `fetchBytes` sat public in Main.kt for one call each in `extractPosterGigs`
and `downloadToCache`; all three are now private in the file that calls them, and
`Element.squarespaceThumbnailUrl` is a member of the one source using it, as `browserUserAgent`
already was. Either question answered the other way leaves it where it is: `fetchPage` has 24 call
sites, and `fetchPosterForClassifying` has one but reads Main.kt's private `imageCacheDir`.

## Wider visibility than anything uses

Make declarations as private as their use allows
(https://dmitrykandalov.com/tidy-kotlin#maximum-privacy): a private one is read with all its use
sites in view, and the compiler answers "used anywhere else?" where a search over a public name
cannot. GigsStore.kt's gig converters, `llmRate`, `cachedImageFile` and every command in Main.kt are
private; `internal` is for the ones only a test reaches, like `eventPageContent`. Public is for what
a public signature exposes - `CompactedLog`, `GigCardView` and five others, where narrowing is a
compile error. Public for any other reason is the smell.

When deciding, check that the outside "use" is a call and not a comment naming the function -
`scrapeGigs` and `compactLog` were each left `internal` on the strength of one, and `DhpVenueGigsSource`
is `internal` today with no use outside its own file at all.

## Argument names the types already give

Distinct argument types mean the compiler already refuses what the labels guard against, so drop them
(https://dmitrykandalov.com/tidy-kotlin#remove-argument-names-when-their-types-are-distinct) -
IntelliJ's "Remove all argument names" does it in one step. Every source builds its
`Gig(GigId(...), GigTitle(...), ...)` that way. Names stay where two arguments share a type
(`LlmRate`'s Doubles, `GigClassified`'s token counts), where the value is a literal that explains
nothing (`url = "https://..."`), and where one is compulsory to skip a defaulted parameter (`seq = 0`).

## A tiny type's wrapped value taken where the type itself would do

`GigTitle("Doom Night")` in an assertion says which type it is, where `.value` against `"Doom Night"`
says only that two strings match - it would pass for a description or a url just as well. Take the
type in signatures too, and no caller has to unwrap: `downloadToCache` takes `PosterUrl`. `.value` is
for what needs the characters - `contains("imgix.net")`, `shortHash`, the text `MisshapenGigsCheck`
measures - and a `String` parameter under a tiny type is what forces the rest.

## Adding to this list

Only shapes seen in this repo, each anchored to a named instance rather than a line number. When an
instance is fixed, point the entry at the next real one or say what the code does instead - an entry
with nothing behind it is the same rot as a comment nobody can check.
