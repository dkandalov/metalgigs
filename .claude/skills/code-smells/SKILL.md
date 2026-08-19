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

Cut on sight: change history, renames, rejected alternatives, restatements of the declaration, which
caller populates a field, explanations of familiar patterns. Keep what reading the code cannot
recover: site behaviour, measurements, library traps (Ovo Arena's `"ImageURL": false`, which Kondor
would fail the whole month's parse over), distinctions between similar things (`logicalDate` versus
`recordedAt`). Wording: sentence capitalisation ending in a full stop, an identifier keeping its case.

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

## Wider visibility than anything uses

Make declarations as private as their use allows
(https://dmitrykandalov.com/tidy-kotlin#maximum-privacy): a private one is read with all its use
sites in view, and the compiler answers "used anywhere else?" where a search over a public name
cannot. GigsStore.kt's gig converters, `llmRate`, `cachedImageFile` and every command in Main.kt are
private; `internal` is for the ones only a test reaches, like `eventPageContent`. Public is for what
a public signature exposes - `CompactedLog`, `GigCardView` and five others, where narrowing is a
compile error. Public for any other reason is the smell.

When deciding, check that the outside "use" is a call and not a comment naming the function -
`scrapeGigs`, `compactLog` and `DiceVenueGigsSource` were each left `internal` on the strength of one.

## Argument names the types already give

Distinct argument types mean the compiler already refuses what the labels guard against, so drop them
(https://dmitrykandalov.com/tidy-kotlin#remove-argument-names-when-their-types-are-distinct) -
IntelliJ's "Remove all argument names" does it in one step. Every source builds its
`Gig(GigId(...), GigTitle(...), ...)` that way. Names stay where two arguments share a type
(`LlmRate`'s Doubles, `GigClassified`'s token counts), where the value is a literal that explains
nothing (`url = "https://..."`), and where one is compulsory to skip a defaulted parameter (`seq = 0`).

## Adding to this list

Only shapes seen in this repo, each anchored to a named instance rather than a line number. When an
instance is fixed, point the entry at the next real one or say what the code does instead - an entry
with nothing behind it is the same rot as a comment nobody can check.
