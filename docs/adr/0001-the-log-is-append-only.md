# 1. The log is append-only, every state is a projection of it, and compaction must project identically

Accepted. Recorded 2026-08-21, describing a design that has stood since 7730c89 (2026-08-08). Amended
2026-08-25: compaction keeps each gig's earliest observation as well as its newest, for `firstSeenAt`.

## Context

Every gig comes from a venue website that changes underneath us. A title gains "- SOLD OUT", a date is
rescheduled, a description is rewritten, a listing moves to a new url when its support act is swapped
in. Nothing upstream carries a version or an identifier we did not derive ourselves, so the only record
of what a venue said is the one we keep.

Two of the things we hold cannot be re-derived at all. An LLM verdict costs a paid call to produce, and
a user's genre override is a human judgement that exists nowhere else. Both have to survive a venue
rewriting the very text they were formed from.

A third thing is a question about time rather than about a gig: which venues are due a rescrape, and
whether the page has already been rendered today. Both are answered from what has happened, not from
what is true now.

## Decision

`events.ndjson` is one append-only file of `LogEntry` values, one JSON object per line: `GigObserved`,
`GigClassified`, `GigReplaced`, `GigsRendered`. `GigsLog.append` is the only writer, and it only ever
appends. No entry is updated or deleted in place.

Every state the application acts on is a projection - a function over `entries`, computed on read. There
is no separate current-state file that could disagree with the log:

| Projection | Answers |
| --- | --- |
| `currentGigs` | the newest observation of each gig, less those a venue has relisted elsewhere |
| `newOrChangedGigs` | what this scrape saw that the log does not already hold |
| `lastScrapedAt` | which venues are outside the scrape cooldown |
| `firstSeenAt` | when each gig arrived, and so which are recently added |
| `alreadyRenderedFor` | whether the page has been rendered as of a date |
| `classificationStatus`, `metalGigs` | what genre a gig is, and so what gets published |
| `alreadyClassified`, `overriddenByUser` | what a forced reclassification may skip |

Which of two entries is the later one is settled by `seq`, a monotonic counter `sequenced` assigns on
append, not by `recordedAt`. A scrape or classification run stamps every entry it appends with one
`Instant`, so equal times are the norm rather than the edge case. `recordedAt` survives for the one
questions that are genuinely about time rather than about order: `lastScrapedAt`, which asks how long ago
a venue was seen, and `firstSeenAt`, which asks when a gig arrived, both read it still.

Compaction is the one operation that destroys anything, and it is defined by what it must preserve
rather than by what it drops: **a compacted log must project identically to the log it replaces.**
`GigsLog.compact` keeps the earliest and the newest observation per gig and the one classification
`effectiveClassification` would pick, and keeps every render and every replacement whole. It hands back
entries rather than writing them, so `compactLog` can write the compacted copy alongside, read it back,
and check six projections agree before anything overwrites `events.ndjson`.

The earliest observation is kept for `firstSeenAt` alone, and is the one thing compaction keeps that no
projection of the log's *current* state reads. It is the case "a new projection is not automatically safe
under compaction" warned of, arriving: a projection wanted later that one observation per gig cannot
answer, met by widening what compaction keeps rather than by giving the projection up.

A gig its venue has relisted keeps its own newest observation like any other, which is easy to mistake
for something compaction could drop. Dropping it would lose the url the gig used to live at, and that
url is the only thing tying the gig listed now to the one already classified - `inheritedFrom` walks it
to find the verdict a relisted gig answers with. Nothing is recorded against a url a venue has only just
written, so a venue renaming a listing costs neither a paid call nor the verdict a user typed. It walks
the chain rather than one step, so a gig moved twice still reaches back to where it was judged, and it
leaves out gigs that answer for themselves entirely.

`firstSeenAt` walks the same chain for a different answer. A gig its venue relisted is the same night in
the same room, so it arrived when the gig it replaced did, not when the new url was written - a filter
calling it newly added would fill with gigs nobody has to hear about twice. A gig moved twice reaches
back through both. Both walks read one map, `replacedBy`: from each gig that replaced another to the gig
it replaced, which is the direction away from what is listed now and back towards where it started.

## Consequences

The file grows without bound in the ordinary course of running. A gig re-observed on a later scrape is
written again in full, event page text and all, and only compaction takes any of it back.

History can be asked questions nobody anticipated when it was written. `GigReplaced` and the token and
model fields on `GigClassified` were both added to a log that already held thousands of lines, and both read
back over entries recorded before they existed - the Kondor converters make such fields optional, and
`JGigClassified` shows the shape.

What compaction loses is the history between a gig's two ends: when it gained "- SOLD OUT", when its
text was captured, which model judged a verdict since superseded.

The compactions run before this rule changed took the arrival dates of the gigs on the page at the time
with them, and those were left as they are rather than reconstructed: the gigs carrying a late date age
off the page within weeks, and the log dates every gig that arrives after correctly.

**A new projection is not automatically safe under compaction.** The six checks in `compactLog` are the
enforcement, and a projection that compaction could change belongs among them. The one that is absent -
`alreadyRenderedFor` - is safe for a reason worth stating: compaction keeps every `GigsRendered` entry,
which is exactly what it reads. `firstSeenAt` is the case in point: it depends on an observation
compaction used to drop, so the rule changed to keep what it needs and the check went in beside it.

Changing how an existing field is written on disk is a migration, not an edit: the log holds lines written
by every earlier version of the code. The `migrate-log-format` skill covers it.

## Alternatives rejected

**A mutable current-state store**, rewriting each gig's row as it is scraped. It cannot answer
`newOrChangedGigs`, which is the whole basis of both the scrape cooldown and of noticing that a venue's
selectors have started returning something else. It would also have made `GigReplaced` unimplementable
after the fact, since the old url would have been overwritten rather than kept.

**A dedicated scrape-event type** to make `lastScrapedAt` exact rather than deriving it from
`GigObserved`. The derivation is wrong only in the safe direction: a venue with no changes for longer
than the cooldown reads as stale and is rescraped anyway, so it is scraped slightly more often than
strictly necessary, never less. An extra entry type per venue per run buys accuracy nothing acts on.

**Working out replacements on read** instead of recording `GigReplaced`. What a replacement rests on is
that a particular run's listing no longer held the old url, and no later run can see that.

**Ordering by `recordedAt`**, which is what the log did until 70bea29. A run's entries all share one
timestamp, so every projection asking for the latest entry was resolving those ties by accident, and
`maxBy` returns the *first* maximal element - the oldest of a tied group was winning.

**Compacting in place**, on the grounds that `events.ndjson` is committed and git makes any swap
recoverable. It is recoverable either way - but a failed check should cost nothing at all, and writing
alongside makes that so.

## Where this is enforced

`LogCompactionTest` holds the compaction rule, including `projects the same gigs and genres as the log it
replaces`, `dates a gig's arrival the same way after compaction as before`, and the trap beneath `keeps a
user override over an LLM classification recorded after it`. `GigsStoreTest` holds the projections and the
sequencing, including `takes the later of two observations recorded at the same instant`, `dates a gig from
its earliest observation, however often its listing changed after`, and `reads back a classification written
before llmModel and useVision existed`.
