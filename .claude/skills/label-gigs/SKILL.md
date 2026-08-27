---
name: label-gigs
description: Work through gigs for the classifier evaluation set - show the user a batch of gigs awaiting labels, optionally only ones the classifiers disagree over, take their genre calls, and append them to the approved train/test set. Use when asked to label gigs, build or grow the evaluation set, or work through the labelling backlog.
---

The set this builds is what a classifier is scored against, so every row in it is a person's call.
Never label a gig on the user's behalf, and never infer one from what the classifiers said - a gig
the classifiers disagreed over is in front of the user precisely because it isn't obvious.

1. Show the next batch. There is nothing to mine first: what to offer is the log's own judged gigs
   less whatever the set has settled, in a stable order, so a batch is always ready.

   ```bash
   .claude/scripts/label-gigs.sh gigs-awaiting-labels 5
   ```

   That costs nothing and asks no model. Adding `--models-to-find-disagreements` runs the named
   ollama models over the gigs waiting and keeps only the ones they and the log answer
   differently:

   ```bash
   .claude/scripts/label-gigs.sh gigs-awaiting-labels 5 --models-to-find-disagreements gemma4:26b-mlx
   ```

   Roughly a second per gig asked about, and it stops as soon as the batch is full - so a batch of
   five costs whatever it takes to find five, which rises as the disagreements are used up. Nothing
   remembers which gigs were asked about, so a batch that comes back short is the signal they are
   worked out. Prefer a plain batch by default - a set built only from disagreements is all corner cases,
   and where the classifiers share a blind spot they agree confidently and wrongly, so the gigs they
   agree on are the only place that shows up. LOVE/HATE was found that way.

2. Post the batch to the user: each gig as a clickable title with its venue and date, what the
   classifiers said if the models were asked, and the part of the copy the call turns on. The copy is
   printed whole and can run to a page, so quote what decides it rather than all of it - but read
   all of it first. A lineup or a named genre is often a long way down, and the one time this was
   cut short it produced a wrong summary to the user.

3. Ask with the AskUserQuestion tool, one question per gig, options `Metal`, `Non-metal` and `Skip`.
   At most 4 questions per call, so a batch of 5 is one call of 4 and one of 1. Say "Non-metal"
   rather than "Other" throughout - the user is confirming it isn't metal, not agreeing with how a
   classifier bucketed it. `Skip` is for one they can't judge; it comes back next time.

   A gig whose page names no genre at all - a band list, or doors and prices and nothing else - is
   worth offering to exclude rather than label, because in the pipeline that gig is what the poster
   path is for. But excluding is not the only answer to a silent page, and usually not the right
   one: a page that gives no reason to call it metal supports Non-metal perfectly well, and that is
   a label worth having, marked off-page per step 4. Exclude where there is nothing to score at all;
   label and mark where there is. Ask rather than deciding - where the line falls is the user's
   call, and a gig they have already labelled shouldn't be quietly turned into an exclusion behind
   them.

4. Ask for a reason alongside anything surprising - a gig they call the opposite of the log, or one
   the classifiers all agreed on and they overturn. One clause is enough ("thrash covers band",
   "jazz despite the riffs"). The reason is stored with the row and is what tells a real edge case
   from a mislabelled one when the set is read back months later.

   Where the reason turns out to be something the page doesn't say - "I know that band", a judgement
   from outside the copy - the row needs marking, because it is right and unreachable: no wording of
   a prompt gets there from that text, and unmarked it reads later as a classifier failing. Ask,
   don't infer, and ask while the judgement is fresh; nobody can reconstruct afterwards which rows
   rested on knowing the band. A band famous enough that naming it is effectively common knowledge
   still counts as on-page - the title carries it.

5. Write the labels to `build/pending-labels.txt`, one per line,
   `<metal|other|exclude>[-offpage] <url> <why>`:

   ```
   metal https://dice.fm/event/... thrash covers, not a tribute night
   other https://www.windmillbrixton.co.uk/events/... jazz with heavy guitars
   metal-offpage https://www.ticketmaster.co.uk/event/... southern rock band the page only calls rockers
   ```

   Then record them, naming the file so it is plain where the labels came from. Each is appended to
   the train or test half according to its own url. The file is left alone afterwards - recording it
   twice adds nothing, because the set already holds those gigs:

   ```bash
   .claude/scripts/label-gigs.sh record-labels build/pending-labels.txt
   ```

6. Report how many were added and what the set now holds in each half. Offer the next batch. Don't
   run more than a few batches without checking the user still wants to continue - this is their
   attention being spent, and a rushed label is worse than no label.

The set lives in `src/test/resources/metalgigs/classify/labelling/`, as `train.ndjson`,
`test.ndjson` and `excluded.ndjson`, and is committed. Which half a gig lands in is settled by its
url, so nobody chooses per example and an example never moves between halves. Don't hand-edit those
files to move a row: if a label is wrong, correct the genre in place and say why in the reason.
