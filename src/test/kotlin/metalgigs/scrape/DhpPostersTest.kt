package metalgigs.scrape

import metalgigs.*
import strikt.api.expectThat
import strikt.assertions.containsExactly
import java.time.Instant
import kotlin.test.Test

// Why a listed gig keeps what the log holds: docs/adr/0014-a-venue-that-changes-platform-keeps-its-artwork.md
class DhpPostersTest {

    private val dhpPoster = PosterUrl("https://www.thegrace.london/wp-content/uploads/2026/06/izaro.png")
    private val listedPoster = PosterUrl("https://alttickets-9a2.kxcdn.com/static_alt_tickets/images/campaign/400x400/izaro.jpg")

    @Test
    fun `a listed gig keeps the DHP poster and copy the log holds, and takes the rest of what is listed`() {
        val logged = gig(url = "19-00", title = "IZARO", poster = dhpPoster, description = "Their own copy")
        val listed = gig(url = "19-00", title = "Izaro", poster = listedPoster, description = "")

        expectThat(keepingDhpPosters(listOf(listed), listOf(logged), emptyList()))
            .containsExactly(gig(url = "19-00", title = "Izaro", poster = dhpPoster, description = "Their own copy"))
    }

    @Test
    fun `a gig moved to a new url in this run keeps them too`() {
        val logged = gig(url = "19-00", title = "IZARO", poster = dhpPoster, description = "Their own copy")
        val listed = gig(url = "18-00", title = "Izaro", poster = listedPoster, description = "")
        val moved = GigReplaced(logged.id, listed.id, Instant.EPOCH)

        expectThat(keepingDhpPosters(listOf(listed), listOf(logged), listOf(moved)))
            .containsExactly(gig(url = "18-00", title = "Izaro", poster = dhpPoster, description = "Their own copy"))
    }

    @Test
    fun `a gig the log holds under any other poster takes what is listed`() {
        val logged = gig(url = "19-00", title = "Izaro", poster = listedPoster, description = "Old copy")
        val listed = gig(url = "19-00", title = "Izaro", poster = PosterUrl("https://alttickets-9a2.kxcdn.com/new.jpg"), description = "New copy")

        expectThat(keepingDhpPosters(listOf(listed), listOf(logged), emptyList())).containsExactly(listed)
    }

    @Test
    fun `a gig the log has never held takes what is listed`() {
        val listed = gig(url = "19-00", title = "Izaro", poster = listedPoster, description = "")

        expectThat(keepingDhpPosters(listOf(listed), emptyList(), emptyList())).containsExactly(listed)
    }

    private fun gig(url: String, title: String, poster: PosterUrl, description: String) = Gig(
        GigId(VenueId("the-grace"), GigUrl("https://www.alttickets.com/izaro-tickets/london-the-grace/2027-11-13-$url")),
        GigTitle(title),
        GigDate(2027, 11, 13),
        poster,
        GigDescription(description),
    )
}
