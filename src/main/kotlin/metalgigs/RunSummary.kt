package metalgigs

import dev.forkhandles.result4k.Result4k
import dev.forkhandles.result4k.valueOrNull
import java.time.Duration

// Only daily-update can print this: scrape doesn't know what will be classified, and classify
// doesn't know what was listed.
internal fun venueRunTable(runs: List<VenueRun>): List<String> {
    val rows = runs.sortedWith(listingsFirst).map(::cellsFor)
    val widths = tableColumns.indices.map { column -> (rows + listOf(tableColumns)).maxOf { it[column].length } }

    return (listOf(tableColumns) + rows).map { cells ->
        cells.mapIndexed { column, cell ->
            when (column) {
                0 -> cell.padEnd(widths[column])
                cells.lastIndex -> cell
                else -> cell.padStart(widths[column])
            }
        }.joinToString("  ").trimEnd()
    }
}

// The counts are what the venue's listing said rather than what was logged from it, so they always
// add up to what it listed: a venue whose gigs validation withheld still listed them, and the
// problems are where that shows.
internal data class VenueRun(
    val venueId: VenueId,
    val listing: VenueListing,
    val classified: Int = 0,
    val took: Duration? = null,
    val problems: List<String> = emptyList(),
)

// A venue scrape gets no further than its own source, so a venue that failed still took whatever it
// spent finding that out - a listing that ends in a timeout is one of the slowest of the run.
internal data class ScrapeAttempt(val venueId: VenueId, val gigs: Result4k<List<Gig>, Exception>, val took: Duration)

internal sealed interface VenueListing {
    data class Listed(val listed: Int, val new: Int, val changed: Int) : VenueListing {
        val old = listed - new - changed
    }

    data object Failed : VenueListing

    data object SkippedByCooldown : VenueListing

    // Classify considers every venue in the log, scrape only the ones with a source, so a
    // poster-only venue reaches the table without any run having scraped it.
    data object NotScraped : VenueListing
}

// A gig the log has never held is new and one it holds under an older listing has changed, which
// newOrChangedGigs answers as the one set. alreadyLogged is what tells them apart, so it has to be
// the gigs the log held before this run appended to it.
internal fun venueRunsFrom(
    skipped: List<VenueId>,
    attempts: List<ScrapeAttempt>,
    newOrChanged: List<Gig>,
    alreadyLogged: Set<GigId>,
    problems: Map<VenueId, List<String>>,
): List<VenueRun> {
    val newOrChangedByVenue = newOrChanged.groupBy { it.id.venueId }

    return skipped.map { VenueRun(it, VenueListing.SkippedByCooldown) } +
        attempts.map { attempt ->
            val fresh = newOrChangedByVenue[attempt.venueId].orEmpty()
            VenueRun(
                attempt.venueId,
                attempt.gigs.valueOrNull()?.let { venueGigs ->
                    VenueListing.Listed(
                        listed = venueGigs.size,
                        new = fresh.count { it.id !in alreadyLogged },
                        changed = fresh.count { it.id in alreadyLogged },
                    )
                } ?: VenueListing.Failed,
                took = attempt.took,
                problems = problems[attempt.venueId].orEmpty(),
            )
        }
}

internal fun withClassifications(
    scraped: List<VenueRun>,
    classified: Map<VenueId, Int>,
    failedToClassify: Map<VenueId, Int>,
): List<VenueRun> {
    val joined = scraped.map { run ->
        run.copy(
            classified = classified[run.venueId] ?: 0,
            problems = run.problems + couldNotClassify(failedToClassify[run.venueId]),
        )
    }
    val unscraped = (classified.keys + failedToClassify.keys) - scraped.map { it.venueId }.toSet()

    return joined + unscraped.map { venueId ->
        VenueRun(
            venueId,
            VenueListing.NotScraped,
            classified[venueId] ?: 0,
            problems = couldNotClassify(failedToClassify[venueId]),
        )
    }
}

private val tableColumns = listOf("Venue", "Listed", "New", "Changed", "Old", "Took", "Classified", "Problems")

// Venues with nothing to count sink to the bottom, where their state is the only thing to read.
private val listingsFirst = compareBy<VenueRun> { rank(it.listing) }
    .thenByDescending { (it.listing as? VenueListing.Listed)?.listed ?: 0 }
    .thenBy { venue(it.venueId).name }

private fun rank(listing: VenueListing) = when (listing) {
    is VenueListing.Listed -> 0
    VenueListing.Failed -> 1
    VenueListing.SkippedByCooldown -> 2
    VenueListing.NotScraped -> 3
}

private fun cellsFor(run: VenueRun): List<String> =
    listOf(venue(run.venueId).name) + countCells(run.listing) +
        listOf(run.took?.let(::elapsedText).orEmpty(), "${run.classified}", problemCell(run.problems))

// Whole seconds: the fastest venue in a run takes a second or so and the slowest minutes, so
// anything finer is noise between two runs of the same venue.
internal fun elapsedText(took: Duration): String =
    if (took.toMinutes() > 0) "${took.toMinutes()}m${took.toSecondsPart()}s" else "${took.toSeconds()}s"

private fun countCells(listing: VenueListing): List<String> = when (listing) {
    is VenueListing.Listed -> listOf("${listing.listed}", "${listing.new}", "${listing.changed}", "${listing.old}")
    VenueListing.Failed -> listOf("failed", "", "", "")
    VenueListing.SkippedByCooldown -> listOf("skipped", "", "", "")
    VenueListing.NotScraped -> listOf("-", "", "", "")
}

// Every problem is printed in full above the table, so a cell long enough to stretch it past a
// terminal's width is cut here rather than left to wrap.
private const val MAX_PROBLEM_CHARS = 70

private fun problemCell(problems: List<String>): String =
    problems.joinToString("; ")
        .let { if (it.length <= MAX_PROBLEM_CHARS) it else it.take(MAX_PROBLEM_CHARS - 3) + "..." }

private fun couldNotClassify(failed: Int?): List<String> =
    if (failed == null || failed == 0) emptyList() else listOf("$failed gig(s) could not be classified")
