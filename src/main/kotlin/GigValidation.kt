import kotlin.math.ceil

// cross-checks a venue's gigs against each other: real gig-specific text (lineup, ticket info,
// dates) wouldn't coincidentally repeat between different gigs, but sitewide boilerplate the page-
// text extraction failed to strip out (nav, footer, cookie notice, venue address) appears
// identically on every one of that venue's pages. Flags venues where enough gigs are made up mostly
// of such shared text, as candidates for the eventPageContentByVenue treatment - this only surfaces
// the problem rather than trying to auto-strip the shared text, which risks eating real content
// along with the boilerplate.
//
// Checked against the *fraction* of each gig's own words that are shared, not just whether any of
// it repeats at all: real venues often print the same short policy line (an age restriction, an ID
// requirement) on every event page as genuine, correctly-scoped content, and that alone shouldn't
// read as contamination the way a whole nav menu and footer glued onto (or standing in for) the
// actual gig text would. Measured against real scraped data, venues with a scraping bug clustered
// far above this 50% line (0.91-1.00) while a venue with only a recurring disclaimer sat well below
// it (0.76 for one already-fixed venue whose extraction has nothing left to narrow further)
private const val SHARED_PHRASE_WORDS = 6
private const val CONTAMINATED_WORD_FRACTION = 0.5

private fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

private fun wordNGrams(words: List<String>): List<String> = words.windowed(SHARED_PHRASE_WORDS, 1).map { it.joinToString(" ") }

fun likelyContaminatedVenues(gigs: List<Gig>): Map<String, Int> =
    gigs.filter { it.description.isNotBlank() }
        .groupBy { it.id.venue.name }
        .filter { (_, venueGigs) -> venueGigs.size >= 3 }
        .mapNotNull { (venue, venueGigs) ->
            val wordsByGig = venueGigs.associateWith { words(it.description) }
            val ngramsByGig = wordsByGig.mapValues { (_, ws) -> wordNGrams(ws) }
            // a phrase has to recur across at least half that venue's gigs (never fewer than two) to
            // count as shared - one coincidental overlap between two unrelated blurbs isn't boilerplate.
            // Each gig's own windows are deduped first, so a phrase repeated many times within just one
            // gig's own text (e.g. the same short filler line printed several times on one page) isn't
            // mistaken for something shared *across* gigs
            val minSharedBy = ceil(venueGigs.size / 2.0).toInt().coerceAtLeast(2)
            val sharedNGrams = ngramsByGig.values.map { it.toSet() }.flatten()
                .groupingBy { it }.eachCount()
                .filterValues { it >= minSharedBy }
                .keys

            val affected = wordsByGig.count { (gig, ws) ->
                if (ws.isEmpty()) return@count false
                val sharedWindows = ngramsByGig.getValue(gig).count { it in sharedNGrams }
                // each window only proves its own 6 words are shared, but overlapping windows over a
                // long shared run would otherwise be counted many times over, so this caps the estimate
                // at the gig's actual word count rather than trying to track the exact covered span
                val sharedWordEstimate = (sharedWindows * SHARED_PHRASE_WORDS).coerceAtMost(ws.size)
                sharedWordEstimate.toDouble() / ws.size >= CONTAMINATED_WORD_FRACTION
            }
            if (affected > 0) venue to affected else null
        }
        .toMap()
