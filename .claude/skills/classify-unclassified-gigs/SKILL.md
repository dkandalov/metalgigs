---
name: classify-unclassified-gigs
description: Show the user the next 5 soonest-upcoming unclassified gigs and apply their genre calls as manual overrides. Use when asked to classify gigs, work through the unclassified backlog, or review/correct upcoming gigs' genre.
---

1. Run `.claude/scripts/run-main.sh "unclassified 5"` from the project root using the Bash tool (see the `run-main` skill). This lists the next 5 soonest-upcoming gigs whose latest classification isn't Metal (including gigs never classified at all) — venue, date, title, and url per gig.

2. If it reports 0 gigs, tell the user the backlog is empty and stop.

3. Ask the user to classify each gig using the AskUserQuestion tool, one question per gig (question text: title, venue, and date), with options `Metal`, `Not metal`, and `Skip`. `Skip` is for a gig the user doesn't recognize or isn't sure about — never guess a genre on their behalf. AskUserQuestion allows at most 4 questions per call, so with 5 gigs ask 4 in one call and the remaining 1 in a second call; fewer than 5 gigs means fewer than 4 questions, so one call is enough.

4. For every gig answered `Metal` or `Not metal` (not `Skip`), run one `override` per gig using that gig's own url from step 1's output:

   ```bash
   .claude/scripts/run-main.sh "override <url> metal"
   .claude/scripts/run-main.sh "override <url> unclassified"
   ```

   Use `metal` for a `Metal` answer and `unclassified` for a `Not metal` answer. Leave skipped gigs alone — they'll surface again next time this skill runs.

5. Report a short summary back to the user: how many were classified Metal, how many Not metal, and how many were skipped.
