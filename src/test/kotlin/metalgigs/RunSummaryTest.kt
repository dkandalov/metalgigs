package metalgigs

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo
import java.time.Duration
import kotlin.test.Test

class RunSummaryTest {

    private val underworld = VenueId("underworld")
    private val dome = VenueId("dome")
    private val blackHeart = VenueId("black-heart")
    private val theDev = VenueId("the-dev")

    @Test
    fun `a listing's counts add up to what it listed`() {
        val run = VenueRun(underworld, VenueListing.Listed(listed = 70, new = 2, changed = 1), took = Duration.ofSeconds(222))

        expectThat(venueRunTable(listOf(run))).containsExactly(
            "Venue           Listed  New  Changed  Old   Took  Classified  Problems",
            "The Underworld      70    2        1   67  3m42s           0",
        )
    }

    @Test
    fun `a venue nothing was scraped from counts nothing, and says why where it can`() {
        expectThat(
            venueRunTable(
                listOf(
                    VenueRun(dome, VenueListing.Failed, problems = listOf("504 Client Error")),
                    VenueRun(blackHeart, VenueListing.SkippedByCooldown),
                )
            )
        ).containsExactly(
            "Venue             Listed  New  Changed  Old  Took  Classified  Problems",
            "The Dome          failed                                    0  504 Client Error",
            "The Black Heart  skipped                                    0",
        )
    }

    @Test
    fun `venues that listed something read first, longest listing down`() {
        val runs = listOf(
            VenueRun(blackHeart, VenueListing.SkippedByCooldown),
            VenueRun(dome, VenueListing.Listed(listed = 70, new = 0, changed = 0)),
            VenueRun(underworld, VenueListing.Listed(listed = 114, new = 0, changed = 0)),
        )

        expectThat(venueRunTable(runs).drop(1).map { it.substringBefore("  ") })
            .containsExactly("The Underworld", "The Dome", "The Black Heart")
    }

    // Classify considers every venue in the log and scrape only the ones with a source, so its
    // counts are joined onto the run's own venues rather than adding any: every venue in the log
    // has a source, and one whose gigs were classified without appearing in the run has none.
    @Test
    fun `classifications join by venue`() {
        val joined = withClassifications(
            listOf(VenueRun(underworld, VenueListing.Listed(listed = 70, new = 2, changed = 0))),
            classified = mapOf(underworld to 2, theDev to 3),
            failedToClassify = mapOf(underworld to 1),
        )

        expectThat(joined).containsExactly(
            VenueRun(underworld, VenueListing.Listed(listed = 70, new = 2, changed = 0), 2, problems = listOf("1 gig(s) could not be classified")),
        )
    }

    @Test
    fun `a gig the log never held is new, one it held under an older listing has changed`() {
        val listed = listOf(gig("a"), gig("b"), gig("c"))

        val runs = venueRunsFrom(
            emptyList(),
            listOf(ScrapeAttempt(underworld, Success(listed), Duration.ofSeconds(9))),
            newOrChanged = listOf(gig("a"), gig("b")),
            alreadyLogged = setOf(gig("b").id, gig("c").id),
            problems = emptyMap(),
        )

        expectThat(runs).containsExactly(
            VenueRun(underworld, VenueListing.Listed(listed = 3, new = 1, changed = 1), took = Duration.ofSeconds(9)),
        )
    }

    @Test
    fun `a venue that failed is timed too, its listing having ended in whatever it spent failing`() {
        val runs = venueRunsFrom(
            emptyList(),
            listOf(ScrapeAttempt(dome, Failure(IllegalStateException("504")), Duration.ofSeconds(30))),
            newOrChanged = emptyList(),
            alreadyLogged = emptySet(),
            problems = mapOf(dome to listOf("504")),
        )

        expectThat(runs).containsExactly(
            VenueRun(dome, VenueListing.Failed, took = Duration.ofSeconds(30), problems = listOf("504")),
        )
    }

    @Test
    fun `elapsed reads in whole seconds, and in minutes once past one`() {
        expectThat(elapsedText(Duration.ofSeconds(9))).isEqualTo("9s")
        expectThat(elapsedText(Duration.ofSeconds(222))).isEqualTo("3m42s")
    }

    @Test
    fun `a problem too long for the table is cut, having been printed in full above it`() {
        val problem = "Failed to fetch https://www.ovoarena.co.uk/events/detail/itzy-1: 504 Client Error: Client Timeout caused by timeout"

        expectThat(venueRunTable(listOf(VenueRun(dome, VenueListing.Failed, problems = listOf(problem)))).last().substringAfter("0  "))
            .isEqualTo("Failed to fetch https://www.ovoarena.co.uk/events/detail/itzy-1: 50...")
    }

    private fun gig(name: String) = Gig(
        GigId(underworld, GigUrl("https://example.com/gigs/$name")),
        GigTitle("Gig $name"),
        GigDate(2026, 8, 8),
        PosterUrl("https://example.com/images/$name.jpg"),
        GigDescription("A gig"),
    )
}
