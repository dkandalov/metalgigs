---
name: code-smells
description: Check a change against the shapes this project rejects, and say so without reverting on your own. Use before writing a comment, before reporting a change complete, when reviewing a diff, and when a fix you are about to write would introduce one of these shapes.
---

A smell is a shape, not a rule. Name it, say what it costs, and keep building - the call on whether
it is worth taking is the user's, and some of these were taken deliberately.

Raise one in a sentence or two at the point you notice it. Do not quietly restructure code to avoid
a smell the user has already accepted, and do not present the smelly shape as clean when you choose
it anyway.

## False and derivable comments

The default is no comment: prose rots while the code around it keeps working. One earns its place by
being true - verified against the code - and not derivable from the declaration, the adjacent code,
or a well-known pattern. Delete a weak one rather than rewording it, and touch only those you are
already editing.

Cut on sight: change history, renames, rejected alternatives, restatements of the declaration, which
caller populates a field, explanations of familiar patterns. Keep what reading the code cannot
recover: site behaviour, measurements, library traps (Ovo Arena's `"ImageURL": false`, which Kondor
would fail the whole month's parse over), distinctions between similar things (`logicalDate` versus
`recordedAt`).

Wording: sentence capitalisation ending in a full stop, an identifier keeping its own case when it
leads.

## A parameter a function takes only for its error message

`posterUrlFrom` in GigsSource.kt takes `gigUrl` for nothing but the string inside its `check`;
`descriptionFrom` takes `url` the same way, and predates it. The identity of the thing being built
gets threaded into a leaf function so a failure can name it.

Instead, attribute the failure where that identity already lives - the call site constructing the
object, or a wrapper whose subject is that object.

Both instances above stand, deliberately: naming the gig turns one unactionable line in the daily
log into a fix. Consistency with them is not an argument for a third.

## A value built only to be rejected a call later

The poster sources used to elide a missing image to `""` and hand it straight to `PosterUrl`, whose
`require` forbids exactly that - an empty string constructed one call before the code that rejects
it. It reads as a tolerated fallback when nothing tolerates it.

Instead, let an absent value stay absent - nullable to the boundary that decides - so the type that
refuses it is the only thing that has to know.

## Implementation details before the declaration they serve

`GigsSource`, the interface the file is named for and every class in it implements, is declared after
the tiny types, the log entries and two private url helpers. Inside each source class the inversion
repeats: `latestGigs`, the interface method, comes last, below the private regexes and
`eventPageContent` it calls.

Declare high-level interfaces, classes, functions and properties before the details that implement
them, so a reader meets what the file is for first and every reference points back towards its top
(https://dmitrykandalov.com/tidy-kotlin#high-level-declarations-first).

GigsSource.kt is like this throughout, so reorder a file you are already restructuring rather than
sweeping.

## Wider visibility than anything uses

`JGig`, `JGigObserved`, `JGigClassified` and `JGigsRendered` are public objects that only `JLogEntry`,
in the same file, ever names. `llmRate` and `LlmRate` are public and reach no further than
`classificationCost` beside them. `cachedImageFile` is public and used once, by `downloadToCache`.

Make values, functions and classes as private as their use allows
(https://dmitrykandalov.com/tidy-kotlin#maximum-privacy). A private declaration is read with all of
its use sites in view and deleted or changed on that evidence, and the compiler answers "is this used
anywhere else" where a search over a public name cannot.

Widening for a test is the accepted exception, and this project takes it: `eventPageContent` on every
source is `internal`, and `renderedFileName` and `genreFromReply` are public, for no other reason.
Reach for `internal` before public when doing so, and don't let a test be the usage that argues for
public.

## Argument names the types already give

Every source builds `Gig(id = ..., title = ..., date = ..., posterUrl = ..., description = ...)`,
where the five types are distinct and each value is a constructor call naming its own type. The
compiler already refuses what those labels guard against, so they are only verbosity
(https://dmitrykandalov.com/tidy-kotlin#remove-argument-names-when-their-types-are-distinct).
IntelliJ's "Remove all argument names" intention drops them in one step.

Two cases keep their names, both live here. A literal names nothing, so
`SquarespaceEventsGigsSource(client, url = "https://...", venue = ourBlackHeart)` keeps `url =`. And
two arguments of one type are exactly what the labels are for - `llmRate` writes
`LlmRate(inputPerMillion = 1.00, outputPerMillion = 5.00)` on one line and `LlmRate(2.00, 10.00)` on
the next, and it is the unnamed pair of Doubles a reader cannot check. `GigClassified`'s
inputTokens/outputTokens and `CompactedLog`'s two counts are the same shape.

## Adding to this list

Only shapes actually seen in this repo, each anchored to a named instance rather than a line number.
When an instance is fixed, point the entry at the next real one or delete it - an entry with nothing
behind it is the same rot as a comment nobody can check.
