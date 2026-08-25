# 4. Every threshold is measured against the log, dated, and set where a real listing cannot reach

Accepted. Recorded 2026-08-25, describing the constants in `GigValidation.kt` and `GigCorrection.kt`.

## Context

Almost every check in ADR 3 comes down to a number, and each decides whether a real venue loses its listing
or a broken source publishes wrong gigs. Numbers chosen from first principles get this wrong both ways: any
`<word>` reads as a tag, but "2026-27 TAEMIN WORLD TOUR <LiMiNaL>" and "ITZY 3RD WORLD TOUR <TUNNEL VISION>"
are real OVO Arena listings; any `&word;` reads as an entity, but 112 titles and 411 descriptions hold a
bare ampersand.

## Decision

Every threshold is measured against the log, and the comment beside it records the measurement, its date,
and how far the number sits from both the real data and the bug it catches.

| Threshold | Set to | What the log said |
| --- | --- | --- |
| `MAX_TITLE_LENGTH` | 200 | 1517 titles: 2 chars shortest, 18 median, 74 at the 99th, 103 longest (2026-08-16) |
| `MAX_DESCRIPTION_LENGTH` | 10,000 | 1126 descriptions: 9 shortest, 730 median, 3826 at the 99th, 7492 longest |
| `MAX_GIGS_A_VENUE_SHOWS_IN_A_DAY` | 5 | 1850 venue-days: 1 median, 2 at the 99th, 4 at the most (2026-08-21) |
| `MAX_GIGS_SHARING_A_POSTER` | 20 | largest real group 7 (Blondies' weekly karaoke), then two of 5, three of 4 |
| `CONTAMINATED_WORD_FRACTION` | 0.5 | bugs clustered 0.91-1.00; a venue with only a recurring disclaimer sat at 0.76 |
| `DAYS_A_LISTING_REACHES_INTO` | 14 | Roundhouse's soonest gig 18 days off with nothing wrong; real gaps of 32, 28, 27, 26 |
| `LEAST_SIMILAR_TITLE` | 0.4 | real moves scored 0.44, 0.60, 0.86; nearest false pair 0.33 (1874 gigs, 2026-08-21) |

**A cap is set where a bug cannot land near it.** A selector that swallowed a card or a page lands far
beyond any title or description a promoter writes, so the margin between 103 and 200, or 7492 and 10,000,
costs nothing and leaves room for a wordier promoter than any seen yet. A collapsed listing is 10 to 122
gigs on one day, nowhere near 5; a broken poster selector takes the whole listing, not 21.

**Neither text field has a lower bound beyond non-blank**, because any minimum big enough to catch a bug
rejects real gigs - the shortest real title is two characters.

Where a number cannot separate the cases, the answer is a different question rather than a better number:

- *Length cannot find a bot wall.* The worst in the log, Facebook's consent page standing in for a gig, runs
  to 5990 characters and sits between two real band biographies. So they are caught by what they say, and
  the phrase list is narrow: "privacy policy" and "terms and conditions" appear in real blurbs that merely
  link them. Three phrases and a leading `{` match every junk description in the log and nothing else.
- *Similarity cannot find a relisted gig.* Union Chapel's two sittings of one show (0.60) and The O2 Forum's
  night beside its 4-day ticket (0.80) rank above one real move (0.44) and level with another. What
  separates them is that the sittings are all still listed, so only a gig the venue stopped listing is a
  candidate (ADR 5); the threshold then only separates what remains.
- *A count cannot find a placeholder poster.* 20 sits deliberately above Live Nation's own
  `defualt-event-image-amg.jpg`, which stands in for 3 gigs at O2 Academy Islington - the same picture doing
  the same job as a residency's artwork, and a venue whose artwork is late is not a broken source.

The unparsed-text patterns follow: only tag names HTML itself uses, each closed by a word boundary; an
entity needs its semicolon; mojibake is matched as the byte pairs a UTF-8 misread leaves rather than by the
characters in them, a lone A-tilde being a letter some band may yet use.

Where a threshold's *shape* matters it is recorded. `CrowdedDayCheck`'s ceiling is what one venue can run in
an evening, so it holds still - adding sources does not raise it, a thinning listing does not lower it.
`SharedPosterCheck`'s is set by a weekly night and grows with how far ahead a venue lists: 20 is about five
months of one.

## Consequences

Each threshold carries the date it was measured, so it can be re-measured against a grown log rather than
argued about. Some are known to report real venues: a venue growing into a sixth event in one evening, or
listing a year of the same Sunday, is reported rather than being a bug. `NothingSoonCheck` sits so close to
real behaviour that a named venue is one to go and look at, which is why it withholds nothing (ADR 3).

## Alternatives rejected

**First-principles patterns for unparsed text** - both match real listings, in numbers. **A length bound on
bot walls** - the worst is longer than most real copy. **Similarity alone for relisted gigs** - real sittings
score higher than real moves. **A count catching AMG's default poster** - it cannot be told from a residency.
