# 13. A classifier is scored against gigs a person labelled, kept whole and split by their url

Accepted. Recorded 2026-08-27, describing `classify/labelling/` and its set under
`src/test/resources/metalgigs/classify/labelling/`.

## Context

Changing the classifier - its prompt, its model, its threshold (ADR 12) - changes what the page publishes,
and until now nothing said whether a change was an improvement. What was available to measure against was
the log's own verdicts, so every number was agreement with the model already in use: a run scoring 94%
against `claude-haiku-4-5` says the two agree, not that either is right.

That is not a small distinction here. Six of the first forty-five gigs a person ruled on were punk gigs
the log calls Metal and a person calls Non-metal, and the local models got all six right. Measured against
the log those six read as the local models being wrong.

A prompt rewritten after reading where a model went wrong is also fitted to the gigs that revealed it. One
such rewrite scored 96% on the sample it was written against and 94% on a disjoint one - the gain was the
sample, not the prompt, and only a second sample said so.

## Decision

**The set is gigs a person ruled on, and only that.** Each row is a `Gig`, a `Genre`, and why. Nothing a
classifier said is ground truth, which is the whole point of the set existing.

**A row keeps the gig whole rather than a url into the log.** A set of pointers would be re-read through
whatever the scrapers say today, so a venue rewording its page (ADR 7) or a gig being relisted at a new
url (ADR 5) would move the ground truth under a score meant to be comparable across months. What was
judged is what is kept, written with the log's own `JGig` so that one converter describes a gig
everywhere - and so the migrate-log-format skill covers the set as well as the log.

**Which half a row lands in is read off its url, three in ten to test.** The split is settled by
`Math.floorMod(url.hashCode(), 10)`, not chosen: the same gig always lands in the same half however often
the set is rebuilt, and nobody can move an example out of the half it is failing in. A String's hash is
specified by the JDK rather than left to an implementation, so this holds across machines and runs.

**A row records whether the page carries the answer at all.** Some labels are right and unreachable: a
page saying only "the southern rockers" is a metal gig if you know the band, and no wording of a prompt
gets there from that text. `canBeDerivedPurelyFromText` marks those, because a score that counts them as
failures is measuring the listing's silence rather than the classifier. It is recorded while the judgement
is fresh - nobody can tell afterwards which rows rested on knowing the band. Where the band is famous
enough that naming it is common knowledge, the title carries it and the row is not marked.

**A gig with nothing to score is excluded rather than labelled**, into a file of its own. It counts against
being offered again but towards nothing a classifier is measured on. A silent page usually does not
qualify: a page giving no reason to call a gig metal supports Non-metal perfectly well, and that is a label
worth having, marked as above.

**Nothing is queued.** What to offer next is a question the log and the set already answer together - every
gig the log has a verdict of its own for, judged on its own page text, less whatever the set has settled -
so the gigs are read from the log both when a batch is shown and when its labels are recorded. A person
therefore rules on the words that get stored, and a gig its venue has moved leaves the batch rather than
being labelled at a url it no longer lives at.

**Asking models to find disagreements is an option, not the default.** Gigs several classifiers answer
differently are where a label buys most, but a set built only from those is all corner cases and will not
transfer. It is also blind by construction to what they get wrong together: `LOVE/HATE`, a glam metal band
at a metal venue, was called Non-metal by all three and found only because batches include gigs they agree
on. When models are asked, they are asked one gig at a time and stopped as soon as the batch is full, so
the cost is set by how many labels are wanted rather than by a window nobody has a way to choose.

**The loop is a `main()`, not a gated test class like the other experiments.** Recording writes to a
committed file, and a JUnit class is discovered by `gradle test`, so labels left waiting in `build/` would
be recorded as a side effect of running the suite - on the strength of a file happening to exist.

## Consequences

A classifier can be scored against something other than its predecessor, and a change can be tuned on the
train half and checked against the test half. What that costs is a person's attention, one gig at a time,
which is the scarce thing here - so how a batch is chosen matters more than how fast it is classified.

The set is small and stays small. Forty-five rows will catch a gross regression and will not resolve two
percentage points between two prompts; a test half of thirteen holds three or four Metal rows, too few to
score recall on, which is the number that matters when a missed metal gig vanishes from the page silently
and a spurious one is merely visible. Growing it means labelling, and labelling Metal-heavy gigs first.

The rows record a rule set nobody wrote down before: metal named on the page counts however small a part
of the act it is, punk named on the page does not, a covers act is judged by the material it plays but a
DJ night playing a mixed list is not an act performing material, and a page naming nothing is Non-metal.

## Alternatives rejected

**Scoring against the log's verdicts** - that measures agreement with the classifier in use, which is the
thing under test. **Storing rows as urls into the log** - the ground truth would move whenever a venue
reworded a page. **Choosing the train/test split per example** - an example could then be moved out of the
half it fails in. **One `accuracy` figure** - the set is deliberately not representative, and overall
agreement is dominated by the easy Non-metal majority; recall and precision on Metal are what transfer.
**Excluding every silent page** - most of them support a Non-metal label, and dropping them would remove
the cases where a classifier guesses. **Mining a queue of candidates up front** - it caches classifier
output that goes stale against the pages it was read from, and needs a second file to hold what the log
and the set already say between them.
