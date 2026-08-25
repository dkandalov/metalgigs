# 12. A genre is one paid call, judged on the gig's own page text or, where there is none, its poster

Accepted. Recorded 2026-08-25, describing `GigClassifier.kt`.

## Context

Every gig needs a genre before it can be published, and nothing in a listing says what one is. The only
evidence is what the venue wrote about the gig (ADR 7) and the poster published with it, and the verdict
comes from an LLM - the one thing here that costs money and cannot be re-derived, which is why the log
keeps it across a venue rewriting the text it was formed from (ADR 1). So a run has three ways to waste a
paid call: repeating one already made, losing ones already made because a later gig failed, and paying for
evidence it did not need.

## Decision

`classifyGigs` skips what is already settled and sorts the rest by date, so a `limit` takes the soonest
gigs. A venue in `alwaysMetalVenues` is settled without a call, recorded as a `User` verdict.

**One gig the model cannot judge must not discard the verdicts before it.** A poster too big to send, an
event page that will not load - collected and reported rather than thrown, those gigs staying Pending for
a later run. Classifying is slow and every call is paid for, so a run throwing away completed work on its
last gig would cost real money twice.

**Which evidence is sent depends on how much the page said.** Below `THIN_TEXT_THRESHOLD` (80 characters)
an event page's text is usually boilerplate or a placeholder rather than anything descriptive, so the
poster goes to a vision-capable model instead of guessing from it; above it, the text alone goes to the
cheaper text model. The system prompt tells the model to read an image the same way - band logos, artwork
style and typography indicate metal without a word of copy.

**Temperature is pinned only on the text path**, the vision model rejecting the override outright and the
text model's verdicts being wanted reproducible.

**The reply is one word, read off the last non-blank line.** The prompt asks for a bare word and usually
gets one, but the model sometimes prefixes a caveat - notably that it cannot identify people in images,
when judging a poster - so a preamble is tolerated while the answer line itself must be just the genre,
give or take trailing punctuation, rather than the genre being fished out of a sentence. The system prompt
heads that caveat off, saying the model is never asked to identify anyone pictured.

`classifierPromptText` is named rather than inlined, so anything measuring another model against the
recorded verdicts asks the same question this asked.

**What a run cost is reported, not estimated.** The API says what it billed, so nothing is computed from
token counts of our own; the usage fields are nullable throughout, and a reply without them is still a
verdict. `classificationCost` returns null rather than zero for anything unpriced - a user override has no
model or tokens, and an entry written before tokens were recorded would read as free - and the report says
how many gigs that was. The two paths are reported separately, differing by more than their rates: a
vision call also carries the poster's image tokens. Money is printed to four places, a call being
fractions of a cent and two places having rounded a whole row to $0.00, with `Locale.ROOT` so the decimal
separator does not follow the machine's locale into a dollar amount. Rates are dated, from pricing read on
2026-08-15: the vision model is on introductory rates until 2026-08-31 and the later ones are already in
the table, so a run after that reports what it cost rather than two thirds of it.

## Consequences

A gig that fails is retried next run at no extra cost, nothing having been recorded for it. Verdicts are
reproducible on the text path and not the vision path, the price of that model refusing the override.

Changing the prompt, the threshold or either model changes what the classifier would say, and the log
holds every verdict already given (ADR 1) - so a change of mind is a `force` reclassification, a paid call
each, which is why it is never what a routine run does.

The rate table is keyed by model name and every classification records the model that made it, so history
can be priced after the fact, including entries from a model no longer in use.

## Alternatives rejected

**Failing the run on one gig** - it discards paid work, and the failure is usually about that gig's poster
or page rather than the run. **Sending the poster with every gig** - most event pages say plenty, and
every call would pay image tokens for evidence adding nothing. **Guessing from thin text** - below 80
characters it is boilerplate, so the model would judge the venue's furniture. **Pinning temperature on
both paths** - the vision model rejects it. **Fishing the genre out of a sentence** - a reply that is prose
did not follow the format, and reading a genre out of it hides that. **Estimating cost ourselves** - the
API says what it billed. **Zero for unpriced entries** - a user override would read as a free LLM call.
**One combined total** - it hides that the paths differ by image tokens, not just by rate.
