import kotlin.math.ceil

// cross-checks a venue's gigs against each other: real gig-specific text (lineup, ticket info,
// dates) wouldn't coincidentally repeat between different gigs, but sitewide boilerplate the page-
// text extraction failed to strip out (nav, footer, cookie notice, venue address) appears
// identically on every one of that venue's pages. Flags venues where enough gigs are made up mostly
// of such shared text, as candidates for scoping that source's eventPageContent - this only surfaces
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

// A gig's title and description are whatever text a selector returned, so a selector that has
// stopped matching, or started matching a container rather than the thing inside it, shows up in
// neither field's type - only in its shape. Both would otherwise be logged and published in silence.
//
// The bounds come from the log as of 2026-08-16, and neither field has a lower one worth setting
// beyond non-blank, because any minimum big enough to catch a bug rejects real gigs. Across 1517
// distinct titles: 2 characters at the shortest ("LP", "AZ", "JJ"), 18 at the median, 74 at the 99th
// percentile, 103 at the longest, with only that one above 100 and nothing above 120. Across 1126
// distinct descriptions: 9 at the shortest, 730 at the median, 3826 at the 99th percentile, 7492 at
// the longest, with six above 5000 and none above 10000.
//
// Each cap is set well clear of that - a selector that swallowed a whole card or a whole page lands
// far beyond either, so the margin costs nothing and leaves room for a wordier promoter than any
// seen yet.
private const val MAX_TITLE_LENGTH = 200
private const val MAX_DESCRIPTION_LENGTH = 10_000

// What a page shows instead of its content: a cookie wall, a bot check, a "turn on JavaScript"
// notice. Length can't find these - the worst of them in the log, Facebook's consent page standing
// in for a gig, runs to 5990 characters and so sits between two real band biographies - so they're
// caught by what they say instead.
//
// Deliberately narrow, because the near misses are real gig copy: "privacy policy" and "terms and
// conditions" both appear in blurbs that merely link them, so neither can be a marker. These three
// phrases and the JSON check match every junk description in the log and nothing else.
private val boilerplatePhrases = listOf(
    Regex("we use cookies", RegexOption.IGNORE_CASE),
    Regex("allow the use of cookies", RegexOption.IGNORE_CASE),
    // both spellings appear: Gigantic writes "Enable JavaScript", tixr writes "Please enable JS"
    Regex("""enable j(ava)?s(cript)?\b""", RegexOption.IGNORE_CASE),
)

private fun readsAsBoilerplate(description: String) =
    description.startsWith("{") || boilerplatePhrases.any { it.containsMatchIn(description) }

// Paired with its reason so a run says which selector to go and look at, rather than only that
// something was wrong.
fun misshapenGigs(gigs: List<Gig>): Map<Gig, String> =
    gigs.mapNotNull { gig ->
        val problem = when {
            gig.title.value.isBlank() -> "no title"
            gig.title.value.length > MAX_TITLE_LENGTH -> "title of ${gig.title.value.length} chars"
            gig.description.isBlank() -> "no description"
            readsAsBoilerplate(gig.description) -> "description is a cookie or bot wall, not gig copy"
            gig.description.length > MAX_DESCRIPTION_LENGTH -> "description of ${gig.description.length} chars"
            else -> null
        }
        problem?.let { gig to it }
    }.toMap()

// A description repeated word for word can only tell one of two stories, and the titles say which.
// The bug is a selector that returns text belonging to the venue rather than to the gig, so the same
// blurb lands on unrelated events; the innocent case is a venue booking the same thing more than
// once - a two-night stand, a weekly residency - where one blurb genuinely covers every date.
//
// Both are common in the log as of 2026-08-17: of 21 groups of gigs sharing a description at the
// same venue, 6 were a bot wall or JS notice repeated across a whole listing and 15 were repeat
// bookings (Blondies' Sunday karaoke, three nights of Leo Kottke at 229, two nights of The Alarm at
// The Garage). Every one of the 15 titles its repeat: "Leo Kottke - SOLD OUT" against "Leo Kottke",
// "(NIGHT 1)" against "(NIGHT 2)", the same club night named identically week after week. So a
// shared word between every title in the group is what separates them, and it has to survive the
// venue's typography - "Paper Dress 80s Club" and "Paper Dress 80's Club" are the same night.
private fun titleWords(title: GigTitle): Set<String> =
    words(title.value.lowercase()).map { it.filter(Char::isLetterOrDigit) }.filter { it.isNotBlank() }.toSet()

fun gigsSharingADescription(gigs: List<Gig>): Map<Gig, String> =
    gigs.filter { it.description.isNotBlank() }
        .groupBy { it.id.venueId to it.description }
        .values
        .filter { group -> group.size > 1 && group.map { titleWords(it.title) }.reduce(Set<String>::intersect).isEmpty() }
        .flatMap { group ->
            group.map { gig ->
                val other = group.first { it != gig }
                gig to "same description as ${group.size - 1} other gig(s) here, e.g. ${other.id.url}"
            }
        }
        .toMap()

private fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

private fun wordNGrams(words: List<String>): List<String> = words.windowed(SHARED_PHRASE_WORDS, 1).map { it.joinToString(" ") }

fun likelyContaminatedVenues(gigs: List<Gig>): Map<VenueId, Int> =
    gigs.filter { it.description.isNotBlank() }
        .groupBy { it.id.venueId }
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
