package metalgigs

import dev.forkhandles.result4k.failureOrNull
import dev.forkhandles.result4k.valueOrNull
import java.io.OutputStream
import java.io.PrintStream

// The columns of venueRunTable that are known while venues are still being scraped: new, changed
// and old need every listing held against the log, and classified is the step after.
internal class ScrapeTable(venues: List<VenueId>, private val toScrape: Int, private val console: PrintStream) {
    // What System.out has to be while a venue is scraped: a source prints what it skipped as it
    // goes, which would otherwise land on the end of that venue's open row.
    val sourceOutput = PrintStream(SourceOutput(), true, console.charset())

    private val widths = listOf(
        (venues.map { venue(it).name } + columns.first()).maxOf { it.length },
        listOf(columns[1], FAILED, SKIPPED).maxOf { it.length },
        WIDEST_TOOK.length,
        0,
    )
    private val placeWidth = place(toScrape).length
    private var rowIsOpen = false

    fun printHeader() = console.println(row("#", columns))

    fun started(place: Int, venueId: VenueId) {
        console.print(opening(place, venueId))
        console.flush()
        rowIsOpen = true
    }

    fun finished(place: Int, attempt: ScrapeAttempt) {
        val listed = attempt.gigs.valueOrNull()?.let { "${it.size}" } ?: FAILED
        val problem = attempt.gigs.failureOrNull()?.let { it.message ?: it.toString() }.orEmpty()
        val row = row(place(place), listOf(venue(attempt.venueId).name, listed, elapsedText(attempt.took), problem))

        console.println(if (rowIsOpen) row.removePrefix(opening(place, attempt.venueId)) else row)
        rowIsOpen = false
    }

    fun skipped(venueId: VenueId) = console.println(row("", listOf(venue(venueId).name, SKIPPED, "", "")))

    private fun opening(place: Int, venueId: VenueId) = row(place(place), listOf(venue(venueId).name))

    private fun row(place: String, cells: List<String>) = "${place.padStart(placeWidth)}  ${alignedRow(cells, widths)}"

    private fun place(place: Int) = "$place/$toScrape"

    private inner class SourceOutput : OutputStream() {
        override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (rowIsOpen) {
                console.println()
                rowIsOpen = false
            }
            console.write(bytes, offset, length)
        }

        override fun flush() = console.flush()
    }
}

private val columns = listOf("Venue", "Listed", "Took", "Problems")

private const val FAILED = "failed"
private const val SKIPPED = "skipped"

// A scrape that outlasts this pushes only its own row out of line.
private const val WIDEST_TOOK = "999m59s"
