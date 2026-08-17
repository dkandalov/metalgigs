import kotlin.math.ceil

// Every check answers the same two questions - what is wrong, and which gigs are not to be logged
// over it - so a whole venue's worth of shared boilerplate and one gig's unparsed title arrive in
// the same shape, and scrapeGigs no longer has to know which check speaks about which.
data class GigsProblem(val detail: String, val gigs: Set<Gig>) {
    init {
        require(gigs.isNotEmpty()) { "A problem with no gigs to point at can't be reported: $detail" }
        require(gigs.map { it.id.venueId }.distinct().size == 1) { "A problem is a venue's to fix, so it can't span several: $detail" }
    }

    val venueId: VenueId get() = gigs.first().id.venueId
}

interface GigsCheck {
    val heading: String
    fun problems(gigs: List<Gig>): List<GigsProblem>
}

data class GigsReport(val heading: String, val problems: List<GigsProblem>)

data class GigsValidation(val reports: List<GigsReport>, val withheld: Set<Gig>)

// Ordered by how precisely each names what went wrong, because that decides which of them speaks
// for a gig several of them catch.
val gigsChecks: List<GigsCheck> = listOf(MisshapenGigsCheck, SharedDescriptionCheck, ContaminationCheck)

// Every gig a source lists is checked, not only the new or changed ones: a venue whose selectors
// have broken serves the same broken text every run, and a gig logged before a check existed is
// wrong until someone is told about it, however long ago it was scraped.
//
// A gig is spoken for by the first check to claim it, so the bot walls that are both misshapen and
// repeated word for word are reported once, as the parsing failure they are. Withholding follows no
// such rule: whatever any check claims stays out of the log.
fun validateGigs(gigs: List<Gig>, checks: List<GigsCheck> = gigsChecks): GigsValidation {
    val withheld = mutableSetOf<Gig>()
    val reports = checks.mapNotNull { check ->
        val problems = check.problems(gigs)
        val worthSaying = problems.filterNot { withheld.containsAll(it.gigs) }
        withheld += problems.flatMap { it.gigs }
        worthSaying.takeIf { it.isNotEmpty() }?.let { GigsReport(check.heading, it) }
    }
    return GigsValidation(reports, withheld)
}

// A gig's title and description are whatever text a selector returned, so a selector that has
// stopped matching, or started matching a container rather than the thing inside it, shows up in
// neither field's type - only in its shape. Both would otherwise be logged and published in silence.
//
// Gigs are gathered by venue and reason so a broken listing reads as the one thing it is, rather
// than as ninety-six of them.
object MisshapenGigsCheck : GigsCheck {
    override val heading = "Gigs that look like a parsing failure - check that source's selectors:"

    override fun problems(gigs: List<Gig>): List<GigsProblem> =
        gigs.mapNotNull { gig -> problemWith(gig)?.let { gig to it } }
            .groupBy { (gig, problem) -> gig.id.venueId to problem }
            .map { (venueAndProblem, found) -> GigsProblem(venueAndProblem.second, found.map { it.first }.toSet()) }

    // The bounds come from the log as of 2026-08-16, and neither field has a lower one worth setting
    // beyond non-blank, because any minimum big enough to catch a bug rejects real gigs. Across 1517
    // distinct titles: 2 characters at the shortest ("LP", "AZ", "JJ"), 18 at the median, 74 at the
    // 99th percentile, 103 at the longest, with only that one above 100 and nothing above 120. Across
    // 1126 distinct descriptions: 9 at the shortest, 730 at the median, 3826 at the 99th percentile,
    // 7492 at the longest, with six above 5000 and none above 10000.
    //
    // Each cap is set well clear of that - a selector that swallowed a whole card or a whole page
    // lands far beyond either, so the margin costs nothing and leaves room for a wordier promoter
    // than any seen yet.
    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_DESCRIPTION_LENGTH = 10_000

    private fun problemWith(gig: Gig): String? = when {
        gig.title.value.isBlank() -> "no title"
        gig.title.value.length > MAX_TITLE_LENGTH -> "title of ${gig.title.value.length} chars"
        gig.description.isBlank() -> "no description"
        readsAsBoilerplate(gig.description) -> "description is a cookie or bot wall, not gig copy"
        gig.description.length > MAX_DESCRIPTION_LENGTH -> "description of ${gig.description.length} chars"
        else -> null
    }

    // What a page shows instead of its content: a cookie wall, a bot check, a "turn on JavaScript"
    // notice. Length can't find these - the worst of them in the log, Facebook's consent page
    // standing in for a gig, runs to 5990 characters and so sits between two real band biographies -
    // so they're caught by what they say instead.
    //
    // Deliberately narrow, because the near misses are real gig copy: "privacy policy" and "terms
    // and conditions" both appear in blurbs that merely link them, so neither can be a marker. These
    // three phrases and the JSON check match every junk description in the log and nothing else.
    private val boilerplatePhrases = listOf(
        Regex("we use cookies", RegexOption.IGNORE_CASE),
        Regex("allow the use of cookies", RegexOption.IGNORE_CASE),
        // both spellings appear: Gigantic writes "Enable JavaScript", tixr writes "Please enable JS"
        Regex("""enable j(ava)?s(cript)?\b""", RegexOption.IGNORE_CASE),
    )

    private fun readsAsBoilerplate(description: String) =
        description.startsWith("{") || boilerplatePhrases.any { it.containsMatchIn(description) }
}

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
//
// Reported as the shared text itself, since what makes this worth chasing is whether it reads as a
// bot wall, a venue's own blurb, or one gig's copy on all the others.
object SharedDescriptionCheck : GigsCheck {
    override val heading = "Gigs given another gig's description word for word - check that source's event page selector:"

    override fun problems(gigs: List<Gig>): List<GigsProblem> =
        gigs.filter { it.description.isNotBlank() }
            .groupBy { it.id.venueId to it.description }
            .values
            .filter { group -> group.size > 1 && group.map { titleWords(it.title) }.reduce(Set<String>::intersect).isEmpty() }
            .map { group -> GigsProblem(shortened(group.first().description), group.toSet()) }

    private const val QUOTED_DESCRIPTION_CHARS = 60

    private fun titleWords(title: GigTitle): Set<String> =
        words(title.value.lowercase()).map { it.filter(Char::isLetterOrDigit) }.filter { it.isNotBlank() }.toSet()

    private fun shortened(description: String): String =
        words(description).joinToString(" ")
            .let { if (it.length <= QUOTED_DESCRIPTION_CHARS) "\"$it\"" else "\"${it.take(QUOTED_DESCRIPTION_CHARS)}...\"" }
}

// Cross-checks a venue's gigs against each other: real gig-specific text (lineup, ticket info,
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
//
// The whole venue is withheld rather than only the gigs measured as contaminated: the fix is to that
// source's scoping, and until it lands its cleaner-looking gigs are only ones that fell the right
// side of a threshold.
object ContaminationCheck : GigsCheck {
    override val heading = "Venues whose gigs may carry site-wide boilerplate - consider scoping their source's eventPageContent:"

    override fun problems(gigs: List<Gig>): List<GigsProblem> =
        gigs.groupBy { it.id.venueId }.values.mapNotNull { venueGigs ->
            val affected = contaminatedGigs(venueGigs.filter { it.description.isNotBlank() })
            if (affected == 0) null else GigsProblem("$affected of ${venueGigs.size} gig(s) mostly shared text", venueGigs.toSet())
        }

    private const val SHARED_PHRASE_WORDS = 6
    private const val CONTAMINATED_WORD_FRACTION = 0.5
    private const val MIN_GIGS_TO_COMPARE = 3

    private fun contaminatedGigs(venueGigs: List<Gig>): Int {
        if (venueGigs.size < MIN_GIGS_TO_COMPARE) return 0

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

        return wordsByGig.count { (gig, ws) ->
            if (ws.isEmpty()) return@count false
            val sharedWindows = ngramsByGig.getValue(gig).count { it in sharedNGrams }
            // each window only proves its own 6 words are shared, but overlapping windows over a
            // long shared run would otherwise be counted many times over, so this caps the estimate
            // at the gig's actual word count rather than trying to track the exact covered span
            val sharedWordEstimate = (sharedWindows * SHARED_PHRASE_WORDS).coerceAtMost(ws.size)
            sharedWordEstimate.toDouble() / ws.size >= CONTAMINATED_WORD_FRACTION
        }
    }

    private fun wordNGrams(words: List<String>): List<String> =
        words.windowed(SHARED_PHRASE_WORDS, 1).map { it.joinToString(" ") }
}

private fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
