---
name: classify-unclassified-gigs
description: Show the user the next 5 soonest-upcoming unclassified gigs and apply their genre calls as manual overrides. Use when asked to classify gigs, work through the unclassified backlog, or review/correct upcoming gigs' genre.
---

1. Run `.claude/scripts/run-main.sh "render"` from the project root using the Bash tool (see the `run-main` skill). There's no standalone command that *lists* unresolved gigs — `render` itself fails fast (writing nothing) whenever unresolved upcoming gigs exist, and that failure message is the list to work from. (`classify status` gives the counts and a per-venue breakdown without running anything, which is worth checking first to see the scale, but it doesn't print the individual gigs or their urls, so it can't drive the steps below.)

2. If the command succeeds (exit 0, no exception), there were no unclassified upcoming gigs — note it renders as a side effect, writing `.rendered/<timestamp>.html`, copying that over `index.html`, syncing `images/`, and appending a `GigsRendered` entry to the log. Tell the user the backlog is empty and stop. If it fails, the failure is an `IllegalStateException` printed to the output starting "N upcoming gig(s) not yet classified ... Soonest 5 ...:" followed by up to 5 gigs, each as **three** lines — `  <date>  <venue>  <title>`, then its status (`  Pending (not yet classified)`), then `  <url>`. That's the list for the next steps; any other kind of failure (e.g. a real compile/network error) isn't this skill's concern — surface it to the user instead of proceeding.

   Note a `Pending` gig is usually one the automated classifier simply hasn't reached yet, so running `classify` would handle it without a human. Only work through those by hand if the user specifically wants to decide them themselves; otherwise suggest `classify` first, which is cheaper on their attention. The exception is a gig `classify` has already tried and failed on — an oversized poster, an event page that won't load — which it reports as "Could not classify N gig(s) - they stay Pending" and will fail on identically every future run. For those a manual override is the only resolution, so don't send the user round the `classify` loop again; say why it can't be judged automatically and ask them directly.

3. Before asking anything, post the 5 gigs to the user as a plain markdown list — title as a link to its url, plus venue and date — so they have something clickable to open and check each one before deciding. Example line: `- [ARCH ENEMY | SOLD OUT](https://www.theunderworldcamden.co.uk/event/arch-enemy/) — The Underworld, 10 Aug 2026`.

4. Then ask the user to classify each gig using the AskUserQuestion tool, one question per gig. Question text: title, venue, date, and the event url (so the link is visible in the question itself too, not just the list above). Options: `Metal`, `Non-metal`, and `Skip`. `Skip` is for a gig the user doesn't recognize or isn't sure about — never guess a genre on their behalf. AskUserQuestion allows at most 4 questions per call, so with 5 gigs ask 4 in one call and the remaining 1 in a second call; fewer than 5 gigs means fewer than 4 questions, so one call is enough.

5. For every gig answered `Metal` or `Non-metal` (not `Skip`), run one `classify override` per gig using that gig's own url from step 1's output. Pass the parts as **separate arguments**, not as one combined string — a gig url can contain characters that a combined string would resplit on:

   ```bash
   .claude/scripts/run-main.sh "classify" "override" "<url>" "metal"
   .claude/scripts/run-main.sh "classify" "override" "<url>" "other"
   ```

   Use `metal` for a `Metal` answer. Use `other` for a `Non-metal` answer — that's just the CLI/`Genre` enum's literal value for "not metal"; always call it "Non-metal" when talking to the user, never "Other", since the user has explicitly confirmed it isn't metal rather than the classifiers having merely bucketed it that way. A `User` override like this is always final — it settles the gig outright regardless of what the classifier said or will say later. Leave skipped gigs alone — they'll surface again next time this skill runs.

6. Report a short summary back to the user: how many were classified Metal, how many Non-metal, and how many were skipped. Use "Non-metal" in this summary too, not "Other".
