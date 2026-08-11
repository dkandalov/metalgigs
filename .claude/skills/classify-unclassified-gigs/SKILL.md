---
name: classify-unclassified-gigs
description: Show the user the next 5 soonest-upcoming unclassified gigs and apply their genre calls as manual overrides. Use when asked to classify gigs, work through the unclassified backlog, or review/correct upcoming gigs' genre.
---

1. Run `.claude/scripts/run-main.sh "render"` from the project root using the Bash tool (see the `run-main` skill). There's no standalone "list unresolved gigs" command — `render` itself fails fast (writing nothing) whenever unresolved upcoming gigs exist, and that failure message is the list to work from.

2. If the command succeeds (exit 0, no exception), there were no unclassified upcoming gigs — it also refreshed `index.html` as a side effect. Tell the user the backlog is empty and stop. If it fails, the failure is an `IllegalStateException` printed to the output starting "N upcoming gig(s) not yet classified ... Soonest 5 ...:" followed by up to 5 gigs, each as **three** lines — `  <date>  <venue>  <title>`, then its status (`  Pending (not yet classified)`), then `  <url>`. That's the list for the next steps; any other kind of failure (e.g. a real compile/network error) isn't this skill's concern — surface it to the user instead of proceeding.

   Note these are gigs the automated classifier simply hasn't reached yet, so running `classify` would handle them without a human. Only work through them by hand if the user specifically wants to decide these themselves; otherwise suggest `classify` first, which is cheaper on their attention.

3. Before asking anything, post the 5 gigs to the user as a plain markdown list — title as a link to its url, plus venue and date — so they have something clickable to open and check each one before deciding. Example line: `- [ARCH ENEMY | SOLD OUT](https://www.theunderworldcamden.co.uk/event/arch-enemy/) — The Underworld, 10 Aug 2026`.

4. Then ask the user to classify each gig using the AskUserQuestion tool, one question per gig. Question text: title, venue, date, and the event url (so the link is visible in the question itself too, not just the list above). Options: `Metal`, `Non-metal`, and `Skip`. `Skip` is for a gig the user doesn't recognize or isn't sure about — never guess a genre on their behalf. AskUserQuestion allows at most 4 questions per call, so with 5 gigs ask 4 in one call and the remaining 1 in a second call; fewer than 5 gigs means fewer than 4 questions, so one call is enough.

5. For every gig answered `Metal` or `Non-metal` (not `Skip`), run one `override` per gig using that gig's own url from step 1's output:

   ```bash
   .claude/scripts/run-main.sh "override <url> metal"
   .claude/scripts/run-main.sh "override <url> other"
   ```

   Use `metal` for a `Metal` answer. Use `other` for a `Non-metal` answer — that's just the CLI/`Genre` enum's literal value for "not metal"; always call it "Non-metal" when talking to the user, never "Other", since the user has explicitly confirmed it isn't metal rather than the classifiers having merely bucketed it that way. A `User` override like this is always final — it settles the gig outright regardless of what the classifier said or will say later. Leave skipped gigs alone — they'll surface again next time this skill runs.

6. Report a short summary back to the user: how many were classified Metal, how many Non-metal, and how many were skipped. Use "Non-metal" in this summary too, not "Other".
