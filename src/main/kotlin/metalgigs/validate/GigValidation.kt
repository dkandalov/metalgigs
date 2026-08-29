package metalgigs.validate

import metalgigs.Gig
import metalgigs.GigDate
import metalgigs.GigDescription
import metalgigs.GigTitle
import metalgigs.PosterUrl
import metalgigs.VenueId
import java.time.temporal.ChronoUnit.DAYS
import kotlin.math.ceil

// Why a named gig costs its venue the listing: docs/adr/0003-a-check-that-names-a-gig-costs-its-venue-the-listing.md
fun validateGigs(
    scraped: Map<VenueId, List<Gig>>,
    today: GigDate,
    previous: List<Gig> = emptyList(),
    checks: List<GigsCheck> = gigsChecks(today),
): GigsValidation {
    val previousByVenue = previous.groupBy { it.id.venueId }
    val spokenFor = mutableSetOf<Gig>()
    val failedVenues = mutableSetOf<VenueId>()
    val reports = checks.mapNotNull { check ->
        val problems = scraped.flatMap { (venue, gigs) ->
            check.problems(venue, gigs, previousByVenue[venue].orEmpty())
        }
        // containsAll would say the opposite of an empty set.
        val worthSaying = problems.filterNot { it.gigs.isNotEmpty() && spokenFor.containsAll(it.gigs) }
        spokenFor += problems.flatMap { it.gigs }
        failedVenues += problems.filter { it.gigs.isNotEmpty() }.map { it.venueId }
        worthSaying.takeIf { it.isNotEmpty() }?.let { GigsReport(check.heading, it) }
    }
    return GigsValidation(reports, scraped.filterKeys { it in failedVenues }.values.flatten().toSet())
}

// Why each threshold below is the number it is: docs/adr/0004-thresholds-are-measured-against-the-log-and-dated.md
private fun gigsChecks(today: GigDate): List<GigsCheck> =
    listOf(EmptyListingCheck, NothingSoonCheck(today), MisshapenGigsCheck, UnparsedTextCheck, DuplicateGigsCheck, CrowdedDayCheck, SharedPosterCheck, SharedDescriptionCheck, ContaminationCheck)

data class GigsValidation(val reports: List<GigsReport>, val withheld: Set<Gig>)

data class GigsReport(val heading: String, val problems: List<GigsProblem>)

// An empty `scraped` means the page was fetched and read, and matched nothing: a venue whose source
// threw never reaches a check.
interface GigsCheck {
    val heading: String
    fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem>
}

data class GigsProblem(val venueId: VenueId, val detail: String, val gigs: Set<Gig>) {
    init {
        require(gigs.all { it.id.venueId == venueId }) {
            "A problem is $venueId's to fix, so it can't point at another venue's gigs: $detail"
        }
    }
}

internal object EmptyListingCheck : GigsCheck {
    override val heading = "Venues that listed no gigs at all - check that source's listing selector:"

    override fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem> =
        if (scraped.isNotEmpty()) emptyList()
        else listOf(GigsProblem(venue, detailFor(previous), emptySet()))

    private fun detailFor(previous: List<Gig>) =
        if (previous.isEmpty()) "listed nothing, and the log holds no gigs for it either"
        else "listed nothing, though the log holds ${previous.size} gig(s) for it"
}

internal class NothingSoonCheck(private val today: GigDate) : GigsCheck {
    override val heading = "Venues with nothing listed in the next fortnight - check what that source has read of its dates:"

    override fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem> {
        if (scraped.isEmpty()) return emptyList()
        val soonest = scraped.map { it.date }.filter { it >= today }.minOrNull()
        return if (soonest != null && soonest.value.isBefore(today.value.plusDays(DAYS_A_LISTING_REACHES_INTO))) emptyList()
        else listOf(GigsProblem(venue, detailFor(scraped, soonest), emptySet()))
    }

    private fun detailFor(scraped: List<Gig>, soonest: GigDate?): String =
        if (soonest == null) "nothing of ${scraped.size} gig(s) is still to come, the latest being ${scraped.maxOf { it.date }}"
        else "soonest of ${scraped.size} gig(s) is $soonest, ${DAYS.between(today.value, soonest.value)} days ahead"

    private companion object {
        // Today counts and the fourteenth day ahead does not, so a fortnight is the fourteen days a
        // venue is currently in rather than fifteen dates.
        const val DAYS_A_LISTING_REACHES_INTO = 14L
    }
}

internal object MisshapenGigsCheck : GigsCheck {
    override val heading = "Gigs that look like a parsing failure - check that source's selectors:"

    override fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem> =
        scraped.mapNotNull { gig -> problemWith(gig)?.let { gig to it } }
            .groupBy { (_, problem) -> problem }
            .map { (problem, found) -> GigsProblem(venue, problem, found.map { it.first }.toSet()) }

    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_DESCRIPTION_LENGTH = 10_000

    private fun problemWith(gig: Gig): String? = when {
        gig.title.value.length > MAX_TITLE_LENGTH -> "title of ${gig.title.value.length} chars"
        gig.description.value.isBlank() -> "no description"
        readsAsBoilerplate(gig.description.value) -> "description is a cookie or bot wall, not gig copy"
        gig.description.value.length > MAX_DESCRIPTION_LENGTH -> "description of ${gig.description.value.length} chars"
        else -> null
    }

    private val boilerplatePhrases = listOf(
        Regex("we use cookies", RegexOption.IGNORE_CASE),
        Regex("allow the use of cookies", RegexOption.IGNORE_CASE),
        // both spellings appear: Gigantic writes "Enable JavaScript", tixr writes "Please enable JS"
        Regex("""enable j(ava)?s(cript)?\b""", RegexOption.IGNORE_CASE),
    )

    private fun readsAsBoilerplate(description: String) =
        description.startsWith("{") || boilerplatePhrases.any { it.containsMatchIn(description) }
}

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

internal object UnparsedTextCheck : GigsCheck {
    override val heading = "Gigs whose text was taken unparsed - check what that source reads it from:"

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

    private val htmlTag =
        Regex("""</?(?:a|p|br|div|span|img|li|ul|ol|em|strong|b|i|h[1-6]|iframe|script|table|tr|td|figure|blockquote)\b[^<>]*>""", RegexOption.IGNORE_CASE)

    private val htmlEntity = Regex("""&(?:[a-zA-Z][a-zA-Z0-9]{1,31}|#\d{1,7}|#[xX][0-9a-fA-F]{1,6});""")

    // What UTF-8 read as Latin-1 leaves behind: a lead byte standing alone in front of a continuation
    // byte, and the E2 80 pair behind every curled quote and dash.
    private val mojibake = Regex("[ÃÂ][-¿]|â€.")
}

internal object CrowdedDayCheck : GigsCheck {
    override val heading = "Venues showing more gigs on one day than they could - check what that source has read of its dates:"

    override fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem> =
        scraped.groupBy { it.date }
            .filterValues { it.size > MAX_GIGS_A_VENUE_SHOWS_IN_A_DAY }
            .entries.sortedBy { it.key }
            .map { (date, onDay) -> GigsProblem(venue, "${onDay.size} gig(s) on $date", onDay.toSet()) }

    private const val MAX_GIGS_A_VENUE_SHOWS_IN_A_DAY = 5
}

internal object SharedPosterCheck : GigsCheck {
    override val heading = "Venues whose gigs are published under one picture - check that source's poster selector:"

    override fun problems(venue: VenueId, scraped: List<Gig>, previous: List<Gig>): List<GigsProblem> =
        scraped.groupBy { it.posterUrl }
            .filterValues { it.size > MAX_GIGS_SHARING_A_POSTER }
            .map { (poster, sharing) -> GigsProblem(venue, "${sharing.size} gig(s) share ${fileName(poster)}", sharing.toSet()) }

    private const val MAX_GIGS_SHARING_A_POSTER = 20

    // The file name is what tells a placeholder from real artwork at a glance, where the url it sits
    // in is mostly host and imgix parameters.
    private fun fileName(poster: PosterUrl) = poster.value.substringBefore('?').substringAfterLast('/')
}

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
        val minSharedBy = ceil(venueGigs.size / 2.0).toInt().coerceAtLeast(2)
        val sharedNGrams = ngramsByGig.values.map { it.toSet() }.flatten()
            .groupingBy { it }.eachCount()
            .filterValues { it >= minSharedBy }
            .keys

        return wordsByGig.count { (gig, ws) ->
            if (ws.isEmpty()) return@count false
            val sharedWindows = ngramsByGig.getValue(gig).count { it in sharedNGrams }
            val sharedWordEstimate = (sharedWindows * SHARED_PHRASE_WORDS).coerceAtMost(ws.size)
            sharedWordEstimate.toDouble() / ws.size >= CONTAMINATED_WORD_FRACTION
        }
    }

    private fun wordNGrams(words: List<String>): List<String> =
        words.windowed(SHARED_PHRASE_WORDS, 1).map { it.joinToString(" ") }
}

private fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
