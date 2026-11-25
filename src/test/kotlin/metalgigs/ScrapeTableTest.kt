package metalgigs

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import kotlin.test.Test

class ScrapeTableTest {

    private val underworld = VenueId("underworld")
    private val dome = VenueId("dome")
    private val blackHeart = VenueId("black-heart")

    private val printed = ByteArrayOutputStream()
    private val console = PrintStream(printed, true)

    @Test
    fun `a venue's row opens as its scrape starts, so the one being waited on is on screen`() {
        val table = ScrapeTable(listOf(underworld, dome), toScrape = 2, console)

        table.printHeader()
        table.started(1, underworld)

        expectThat(printed.toString()).isEqualTo(
            "  #  Venue            Listed     Took  Problems\n" +
                "1/2  The Underworld"
        )
    }

    @Test
    fun `a row is finished on the line it opened on`() {
        val table = ScrapeTable(listOf(underworld, dome), toScrape = 2, console)

        table.printHeader()
        table.started(1, underworld)
        table.finished(1, ScrapeAttempt(underworld, Success(gigs(70)), Duration.ofSeconds(222)))
        table.started(2, dome)
        table.finished(2, ScrapeAttempt(dome, Success(gigs(9)), Duration.ofSeconds(9690)))

        expectThat(printed.lines()).containsExactly(
            "  #  Venue            Listed     Took  Problems",
            "1/2  The Underworld       70    3m42s",
            "2/2  The Dome              9  161m30s",
        )
    }

    @Test
    fun `a venue that failed says why in full, this being the only place it is printed`() {
        val table = ScrapeTable(listOf(dome), toScrape = 1, console)
        val problem = "Failed to fetch https://www.ovoarena.co.uk/events/detail/itzy-1: 504 Client Error: Client Timeout caused by timeout"

        table.started(1, dome)
        table.finished(1, ScrapeAttempt(dome, Failure(IllegalStateException(problem)), Duration.ofSeconds(30)))

        expectThat(printed.lines()).containsExactly(
            "1/1  The Dome   failed      30s  $problem",
        )
    }

    @Test
    fun `what a source prints mid-scrape gets its own line, and the row is printed whole after it`() {
        val table = ScrapeTable(listOf(underworld), toScrape = 1, console)

        table.started(1, underworld)
        table.sourceOutput.println("  reading the August flyer")
        table.sourceOutput.println("Skipping 2 gig(s) with no ticket link")
        table.finished(1, ScrapeAttempt(underworld, Success(gigs(70)), Duration.ofSeconds(222)))

        expectThat(printed.lines()).containsExactly(
            "1/1  The Underworld",
            "  reading the August flyer",
            "Skipping 2 gig(s) with no ticket link",
            "1/1  The Underworld       70    3m42s",
        )
    }

    @Test
    fun `a venue skipped by the cooldown has no place in the run`() {
        val table = ScrapeTable(listOf(underworld, blackHeart), toScrape = 1, console)

        table.printHeader()
        table.skipped(blackHeart)

        expectThat(printed.lines()).containsExactly(
            "  #  Venue             Listed     Took  Problems",
            "     The Black Heart  skipped",
        )
    }

    @Test
    fun `places line up once the run reaches two digits`() {
        val table = ScrapeTable(listOf(underworld), toScrape = 12, console)

        table.printHeader()
        table.started(3, underworld)
        table.finished(3, ScrapeAttempt(underworld, Success(gigs(70)), Duration.ofSeconds(9)))

        expectThat(printed.lines()).containsExactly(
            "    #  Venue            Listed     Took  Problems",
            " 3/12  The Underworld       70       9s",
        )
    }

    private fun ByteArrayOutputStream.lines() = toString().trimEnd('\n').split("\n")

    private fun gigs(count: Int) = (1..count).map { number ->
        Gig(
            GigId(underworld, GigUrl("https://example.com/gigs/$number")),
            GigTitle("Gig $number"),
            GigDate(2026, 8, 8),
            PosterUrl("https://example.com/images/$number.jpg"),
            GigDescription("A gig"),
            Lineup.none,
            status = null,
        )
    }
}
