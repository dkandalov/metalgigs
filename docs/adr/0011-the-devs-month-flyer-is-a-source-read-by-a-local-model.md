# 11. The Dev's month flyer is a source, read by a model running on this machine

Accepted. Recorded 2026-08-25, describing `DevGigSource.kt`.

## Context

The Dev prints nothing per gig. A month's bookings are one flyer posted to Instagram, and that flyer is the
whole listing - no listing page, no event page, no feed, no per-gig url. Every other source here reads markup
or JSON; this one has to read a picture.

## Decision

The source *is* the flyer: found among the account's recent posts by its caption, and read by a local model.

**The flyer is identified by its caption, never by its picture.** The caption says which month the picture is
the what's-on for - "What's On AUGUST 2026! ALL EVENTS ARE FREE ENTRY!" - and a band's own tour poster is the
same typography over the same list of dates, reading as a month's what's-on until you notice the dates are at
other venues. If no recent post is captioned that way, the source fails rather than guessing.

**The caption's month catches a date the model misread.** The flyer prints one month's dates, so a gig dated in
another month is one it never carried: those are printed and dropped. Which flyer was found is printed too,
since it is the whole listing - a run that read the wrong one, or last month's because this month's is not up
yet, says so before the gigs do.

**The model runs on this machine**, currently `gemma4:26b`. Every gig on the flyer is a night at The Dev, so
the model only ever reads dates and names off it - little enough for a local model, which is what makes
re-reading the flyer every scrape affordable where a paid call per run would not be. The image is sent enlarged to 1080 on
its shorter side rather than at the 768px a poster is published at: a genre judgement reads artwork, this has
to read a month of dates and names.

Enlarging is not for detail it cannot add. The three flyers this was fitted to came off Instagram's api at
1080x1440; the page it is now read from serves 480x640 (ADR 8), and at that size `gemma4:26b` read every date
correctly but stopped reading the second line of a two-line row - the bill under the promoter's name, in the
smallest type on the poster. Three of September's nine gigs published a promoter and no bands. Resampling that
same picture to 1080 read all three completely, on an unchanged prompt, while June, July and August - already
at 1080, so passed through untouched - read exactly as before. So the failure was the type being smaller than
what the model resolves, not the prompt being unclear about it, which is worth recording because the prompt is
where it looks like it should be fixed: an attempt to describe the two-line shape there made the model give the
second line a date of its own and invent a gig, and put start times into every title on all four flyers.

**The prompt names the shapes a row comes in.** Asking for "every distinct gig you can identify" was enough for
Sonnet and not for `gemma4:26b`, which left out the one row on the August flyer whose line-up is half
unannounced ("Mur (ISL) + Support TBA") - a row it could read the date of and did not count as a gig. Naming
the shapes recovered it, over ten runs of five prompt variants; asking the model to count the rows first, which
it cannot check, changed nothing. The reply is one `yyyy-MM-dd | Title` per line, and anything not matching
that shape is ignored.

Three consequences of a flyer being the only source are handled where they arise: **the url** is the venue's
Facebook page plus a fragment, never the Instagram post, so a gig keeps the url it was first logged under when
next month's flyer goes up (ADR 5); **the description** is the title, there being no page behind the gig, where
`""` would say a page was read and said nothing (ADR 7); **the title's slashes** settle to one spelling, a
title that flips between runs being a gig logged as changed every run (ADR 6).

The venue's regular karaoke night is excluded by title, printed on the flyer alongside band shows with nothing
but its name marking it apart. The profile is read from the endpoint the page's own script calls, with the web
client's app id (ADR 8); Instagram wraps every list as edges around nodes, twice over here - once for the posts
and once for each post's caption, of which a post has one or none.

## Consequences

This venue's listing is only as good as a local model's reading of a picture, re-read from scratch every scrape
rather than accumulated; the month check and the row format are all that stand between a misreading and a
published gig. The listing lags the venue by up to a month, the printed month making that visible. A model
upgrade changes what is listed, so the extraction model is named in what this source prints and raises.

## Alternatives rejected

**Identifying the flyer by its picture** - a tour poster is the same typography over the same shape of date
list. **A paid vision call per run** - re-reading every scrape is what keeps the source honest, and only a local
model makes that free. **"Every distinct gig you can identify"** - it silently drops partly unannounced rows.
**Asking the model to count rows first** - it cannot check its own count, and it changed nothing over ten runs.
**Linking to the Instagram post** - superseded monthly, so every gig would be relisted at a new url. **Sending
the flyer at published poster size** - a month of dates has to be legible, which is not judging artwork.
**Describing the two-line row in the prompt** - it invented a gig from the continuation line and leaked start
times into every title; the shape reads correctly at 1080 with no prompt change at all.
