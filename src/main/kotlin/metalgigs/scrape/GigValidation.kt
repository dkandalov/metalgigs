package metalgigs.scrape

import metalgigs.Gig
import metalgigs.GigDescription
import metalgigs.GigTitle
import metalgigs.PosterUrl
import metalgigs.VenueId
import kotlin.math.ceil

// Every gig a source lists is checked, not only the new or changed ones: a venue whose selectors
// have broken serves the same broken text every run, and a gig logged before a check existed is
// wrong until someone is told about it, however long ago it was scraped.
//
// Keyed by the venue each source was asked for rather than taking one flat list of gigs, because a
// venue that listed nothing is absent from such a list entirely - there would be nothing to notice.
// The keys carry that, and can't fall out of step with the gigs the way a separate set of ids could.
//
// A gig is spoken for by the first check to claim it, so the bot walls that are both misshapen and
// repeated word for word are reported once, as the parsing failure they are. Withholding follows no
// such rule: whatever any check claims stays out of the log.
fun validateGigs(
    scraped: Map<VenueId, List<Gig>>,
    previous: List<Gig> = emptyList(),
    checks: List<GigsCheck> = gigsChecks,
): GigsValidation {
    val previousByVenue = previous.groupBy { it.id.venueId }
    val withheld = mutableSetOf<Gig>()
    val reports = checks.mapNotNull { check ->
        val problems = scraped.flatMap { (venue, gigs) ->
            check.problems(venue, gigs, previousByVenue[venue].orEmpty())
        }
        // A problem pointing at no gigs can't be one an earlier check already spoke for, and
        // containsAll would say the opposite of an empty set.
        val worthSaying = problems.filterNot { it.gigs.isNotEmpty() && withheld.containsAll(it.gigs) }
        withheld += problems.flatMap { it.gigs }
        worthSaying.takeIf { it.isNotEmpty() }?.let { GigsReport(check.heading, it) }
    }
    return GigsValidation(reports, withheld)
}

// Ordered by how precisely each names what went wrong, because that decides which of them speaks
// for a gig several of them catch.
private val gigsChecks: List<GigsCheck> = listOf(EmptyListingCheck, MisshapenGigsCheck, UnparsedTextCheck, DuplicateGigsCheck, CrowdedDayCheck, SharedPosterCheck, SharedDescriptionCheck, ContaminationCheck)

data class GigsValidation(val reports: List<GigsReport>, val withheld: Set<Gig>)

data class GigsReport(val heading: String, val problems: List<GigsProblem>)

// Called once per venue that was actually scraped, with what that run listed for it and what the
// log already holds. A venue whose source threw never reaches a check - it's reported where it's
// caught - so an empty `scraped` means the page was fetched and read, and matched nothing.
interface GigsCheck {
    val heading: String
    fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem>
}

// Every check answers the same two questions - what is wrong, and which gigs are not to be logged
// over it - so a whole venue's worth of shared boilerplate and one gig's unparsed title arrive in
// the same shape, and the caller doesn't have to know which check speaks about which.
//
// The venue is named rather than read off the gigs, because a venue that listed nothing has a
// problem worth reporting and no gigs to point at.
data class GigsProblem(val venueId: VenueId, val detail: String, val gigs: Set<Gig>) {
    init {
        require(gigs.all { it.id.venueId == venueId }) {
            "A problem is $venueId's to fix, so it can't point at another venue's gigs: $detail"
        }
    }
}

// A listing selector that has stopped matching returns an empty selection rather than failing, so
// the venue leaves the run in silence: nothing is logged for it, nothing is withheld, and the only
// trace is one "0 gig(s) listed" line among every other venue's. The cooldown then reads it as
// unscraped and comes back for it tomorrow, to find the same nothing.
//
// What the log already holds for the venue is what separates the two ways of listing nothing: a
// venue whose gigs are all in the log and have simply stopped appearing is a broken source, while
// one the log has never held any for is more likely a venue that has yet to announce anything.
internal object EmptyListingCheck : GigsCheck {
    override val heading = "Venues that listed no gigs at all - check that source's listing selector:"

    override fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem> =
        if (scraped.isNotEmpty()) emptyList()
        else listOf(GigsProblem(venue, detailFor(previous), emptySet()))

    private fun detailFor(previous: List<Gig>) =
        if (previous.isEmpty()) "listed nothing, and the log holds no gigs for it either"
        else "listed nothing, though the log holds ${previous.size} gig(s) for it"
}

// A gig's title and description are whatever text a selector returned, so a selector that started
// matching a container rather than the thing inside it, or stopped matching a description
// altogether, shows up in neither field's type - only in its shape. Both would otherwise be logged
// and published in silence.
//
// Gigs are gathered by reason so a broken listing reads as the one thing it is, rather than as
// ninety-six of them.
internal object MisshapenGigsCheck : GigsCheck {
    override val heading = "Gigs that look like a parsing failure - check that source's selectors:"

    override fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem> =
        scraped.mapNotNull { gig -> problemWith(gig)?.let { gig to it } }
            .groupBy { (_, problem) -> problem }
            .map { (problem, found) -> GigsProblem(venue, problem, found.map { it.first }.toSet()) }

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
        gig.title.value.length > MAX_TITLE_LENGTH -> "title of ${gig.title.value.length} chars"
        gig.description.value.isBlank() -> "no description"
        readsAsBoilerplate(gig.description.value) -> "description is a cookie or bot wall, not gig copy"
        gig.description.value.length > MAX_DESCRIPTION_LENGTH -> "description of ${gig.description.value.length} chars"
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

// Nothing downstream would notice a source listing one gig more than once. The log takes every copy
// as its own observation, and each projection groups by id and keeps the newest, so a paging loop
// that re-serves a page - or a listing whose gig appears in a "featured" strip as well as the run of
// them - reads only as a venue with more gigs than it has.
//
// Told apart by whether the copies agree, because they are different bugs. Copies that match are one
// listing read twice, and the gig itself is fine. Copies that differ mean the source built two
// different gigs from one url, and which of them the log ends up holding is decided by nothing
// better than which was scraped last.
internal object DuplicateGigsCheck : GigsCheck {
    override val heading = "Gigs their source listed more than once - check that source's listing selector and paging:"

    override fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem> =
        scraped.groupBy { it.id }
            .filterValues { it.size > 1 }
            .map { (_, copies) -> GigsProblem(venue, detailFor(copies), copies.toSet()) }

    private fun detailFor(copies: List<Gig>): String =
        if (copies.distinct().size == 1) "listed ${copies.size} times"
        else "listed ${copies.size} times, and not identically"
}

// Text that was never parsed, only copied. A source reading .html() where .text() was meant, a JSON
// field the site never decoded, or bytes read in the wrong charset - each leaves its own mark, and
// none of them is anything a venue would type.
//
// The patterns are drawn against the log as of 2026-08-21 rather than from first principles, because
// the obvious ones are wrong. Any <word> reads as a tag, but "2026-27 TAEMIN WORLD TOUR <LiMiNaL>"
// and "ITZY 3RD WORLD TOUR <TUNNEL VISION>" are real Ovo Arena listings bracketing a tour's name
// that way, so only the names HTML itself uses count as one. Any &word; reads as an entity, but 112
// titles and 411 descriptions hold a bare ampersand, so the semicolon has to be there. And mojibake
// is matched as the byte pairs a UTF-8 misread leaves rather than by the characters in them, a lone
// A-tilde being a letter some band may yet put in its name.
internal object UnparsedTextCheck : GigsCheck {
    override val heading = "Gigs whose text was taken unparsed - check what that source reads it from:"

    // A title is published, so one carrying markup is withheld like any other parsing failure. A
    // description is not - it is what the classifier reads - and the log's only real case is The
    // Black Heart, whose own event pages print a Bandcamp embed's code as visible text below real
    // gig copy. Withholding those would cost the site actual metal gigs over a venue's broken embed,
    // so the venue is told and the gigs are kept.
    override fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem> =
        byMarker(scraped) { it.title.value }
            .map { (marker, gigs) -> GigsProblem(venue, "title holds $marker", gigs.toSet()) } +
            byMarker(scraped) { it.description.value }
                .map { (marker, gigs) -> GigsProblem(venue, "${gigs.size} description(s) hold $marker, e.g. ${gigs.first().id.url}", emptySet()) }

    private fun byMarker(scraped: List<Gig>, read: (Gig) -> String): Map<String, List<Gig>> =
        scraped.mapNotNull { gig -> markerIn(read(gig))?.let { gig to it } }
            .groupBy({ (_, marker) -> marker }, { (gig, _) -> gig })

    private fun markerIn(text: String): String? = when {
        htmlTag.containsMatchIn(text) -> "unparsed markup"
        htmlEntity.containsMatchIn(text) -> "an html entity"
        mojibake.containsMatchIn(text) -> "mis-decoded characters"
        else -> null
    }

    // Only the tag names HTML itself uses, each closed off by a word boundary, so a tour bracketed
    // as <LiMiNaL> isn't read as a list item and <TUNNEL VISION> isn't read as a table row.
    private val htmlTag =
        Regex("""</?(?:a|p|br|div|span|img|li|ul|ol|em|strong|b|i|h[1-6]|iframe|script|table|tr|td|figure|blockquote)\b[^<>]*>""", RegexOption.IGNORE_CASE)

    private val htmlEntity = Regex("""&(?:[a-zA-Z][a-zA-Z0-9]{1,31}|#\d{1,7}|#[xX][0-9a-fA-F]{1,6});""")

    // What UTF-8 read as Latin-1 leaves behind: a lead byte standing alone in front of a continuation
    // byte, and the E2 80 pair behind every curled quote and dash.
    private val mojibake = Regex("[ÃÂ][-¿]|â€.")
}

// A date parse that has drifted doesn't fail - it returns a date, and the same wrong one for every
// gig it reads, so a listing lands entire on a single day. Nothing about any one of those gigs is
// wrong to look at: only how many of them a venue is showing at once.
//
// Measured over the log as of 2026-08-21, across 1850 venue-days: a venue lists one gig on a day at
// the median, two at the 99th percentile, and four at the most - Roundhouse's Centre 59 Theatre
// Week, then an Alexandra Palace expo day and two of Union Chapel's Camden Fringe. Unlike a day's
// gigs across the whole city, that ceiling holds still. It is what one venue can physically run in
// an evening, so adding sources doesn't raise it and a listing thinning out further ahead doesn't
// lower it, which is what makes it worth setting a number against.
//
// Five leaves a gig's room above the busiest day ever listed, and a collapsed listing lands nowhere
// near it - the smallest listing in the log is 10 gigs and the largest 122, so every venue here
// would be caught by it. A venue that grows into a sixth event in one evening is reported rather
// than a bug, which is the trade for headroom that narrow.
internal object CrowdedDayCheck : GigsCheck {
    override val heading = "Venues showing more gigs on one day than they could - check what that source has read of its dates:"

    override fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem> =
        scraped.groupBy { it.date }
            .filterValues { it.size > MAX_GIGS_A_VENUE_SHOWS_IN_A_DAY }
            .entries.sortedBy { it.key }
            .map { (date, onDay) -> GigsProblem(venue, "${onDay.size} gig(s) on $date", onDay.toSet()) }

    private const val MAX_GIGS_A_VENUE_SHOWS_IN_A_DAY = 5
}

// A poster selector that has stopped matching doesn't fail - it matches something else, and the
// something else is the same for every card on the page: the venue's logo, its Facebook banner, a
// ticketing network's placeholder. Every gig is then published under one picture, which no other
// check would notice, all of them reading the gigs' text rather than what is shown with it.
//
// Measured over the log as of 2026-08-21, across scraped venues alone - a poster-only venue's gigs
// all carry the one image its poster was read from, by design, and never reach a check. The largest
// group of gigs genuinely sharing a poster is 7, Blondies' weekly Free Karaoke Sundays, then two of
// 5 (a four-night Mission run at the Forum, a recurring comedy night at Signature Brew) and three of
// 4. A broken selector doesn't land near those: it takes the whole listing, which is 20 to 122 gigs
// at every venue here.
//
// Deliberately above Live Nation's own defualt-event-image-amg.jpg, which stands in for 3 gigs at
// O2 Academy Islington. That is the same picture doing the same job, and no count tells it from a
// residency - a venue whose artwork is late is not a source that has broken, which is what this is
// for.
internal object SharedPosterCheck : GigsCheck {
    override val heading = "Venues whose gigs are published under one picture - check that source's poster selector:"

    override fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem> =
        scraped.groupBy { it.posterUrl }
            .filterValues { it.size > MAX_GIGS_SHARING_A_POSTER }
            .map { (poster, sharing) -> GigsProblem(venue, "${sharing.size} gig(s) share ${fileName(poster)}", sharing.toSet()) }

    // A weekly night is what sets this, not the bug: it grows with how far ahead a venue lists, so 20
    // is about five months of one. A venue listing a year of the same Sunday would be reported.
    private const val MAX_GIGS_SHARING_A_POSTER = 20

    // The file name is what tells a placeholder from real artwork at a glance, where the url it sits
    // in is mostly host and imgix parameters.
    private fun fileName(poster: PosterUrl) = poster.value.substringBefore('?').substringAfterLast('/')
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
internal object SharedDescriptionCheck : GigsCheck {
    override val heading = "Gigs given another gig's description word for word - check that source's event page selector:"

    override fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem> =
        scraped.filter { it.description.value.isNotBlank() }
            .groupBy { it.description }
            .values
            .filter { group -> group.size > 1 && group.map { titleWords(it.title) }.reduce(Set<String>::intersect).isEmpty() }
            .map { group -> GigsProblem(venue, shortened(group.first().description), group.toSet()) }

    private const val QUOTED_DESCRIPTION_CHARS = 60

    private fun titleWords(title: GigTitle): Set<String> =
        words(title.value.lowercase()).map { it.filter(Char::isLetterOrDigit) }.filter { it.isNotBlank() }.toSet()

    private fun shortened(description: GigDescription): String =
        words(description.value).joinToString(" ")
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
internal object ContaminationCheck : GigsCheck {
    override val heading = "Venues whose gigs may carry site-wide boilerplate - consider scoping their source's eventPageContent:"

    override fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem> {
        val affected = contaminatedGigs(scraped.filter { it.description.value.isNotBlank() })
        return if (affected == 0) emptyList()
        else listOf(GigsProblem(venue, "$affected of ${scraped.size} gig(s) mostly shared text", scraped.toSet()))
    }

    private const val SHARED_PHRASE_WORDS = 6
    private const val CONTAMINATED_WORD_FRACTION = 0.5
    private const val MIN_GIGS_TO_COMPARE = 3

    private fun contaminatedGigs(venueGigs: List<Gig>): Int {
        if (venueGigs.size < MIN_GIGS_TO_COMPARE) return 0

        val wordsByGig = venueGigs.associateWith { words(it.description.value) }
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
