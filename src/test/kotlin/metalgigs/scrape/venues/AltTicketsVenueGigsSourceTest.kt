package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo
import strikt.assertions.isNull
import kotlin.test.Test
import kotlin.test.assertFailsWith

// Why this reading surface: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
// Why the poster is this size: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
class AltTicketsVenueGigsSourceTest {

    @Test
    fun `extracts gig events from The Garage's AltTickets listing`() {
        assertScrapesGigs(
            source = TheGarageGigsSource(cachedClient()),
            size = 82,
            first = Gig(
                GigId(theGarage.id, GigUrl("https://www.alttickets.com/danko-jones-tickets/london-the-garage/2026-11-05-19-00")),
                GigTitle("Danko Jones"),
                GigDate(2026, 11, 5),
                PosterUrl("https://alttickets-9a2.kxcdn.com/static_alt_tickets/images/campaign/400x400/danko_jones-7713508499.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theGarage.id, GigUrl("https://www.alttickets.com/shonen-knife-tickets/london-the-garage/2027-06-19-19-00")),
                GigTitle("Shonen Knife"),
                GigDate(2027, 6, 19),
                PosterUrl("https://alttickets-9a2.kxcdn.com/static_alt_tickets/images/campaign/400x400/shonen_knife-7919715854.jpg"),
                GigDescription(""),
            ),
        )
    }

    @Test
    fun `extracts gig events from The Grace's AltTickets listing`() {
        assertScrapesGigs(
            source = TheGraceGigsSource(cachedClient()),
            size = 68,
            first = Gig(
                GigId(theGrace.id, GigUrl("https://www.alttickets.com/lance-roark-tickets/london-the-grace/2026-11-05-19-30")),
                GigTitle("Lance Roark"),
                GigDate(2026, 11, 5),
                PosterUrl("https://alttickets-9a2.kxcdn.com/static_alt_tickets/images/campaign/400x400/lance_roark-7745326167.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theGrace.id, GigUrl("https://www.alttickets.com/izaro-tickets/london-the-grace/2027-11-13-19-00")),
                GigTitle("IZARO"),
                GigDate(2027, 11, 13),
                PosterUrl("https://alttickets-9a2.kxcdn.com/static_alt_tickets/images/campaign/400x400/izaro-7816914833.jpg"),
                GigDescription(""),
            ),
        )
    }

    // A sold-out card links to "#" and a not-yet-on-sale one has no link either, so both are read
    // here off the JSON-LD, the only place naming their urls.
    @Test
    fun `reads each gig off the JSON-LD and its event page, ignoring the cards`() {
        val listing = """
            <div class="ticket-data"><h2>Card Only</h2><a href="/card-only-tickets/london-the-garage/2026-12-01-19-00">Book</a></div>
            <script type="application/ld+json">
                [{"@context":"http:\/\/schema.org","@type":"MusicEvent","name":"Messa ",
                  "url":"https:\/\/www.alttickets.com\/messa-tickets\/london-the-garage\/2026-11-07-18-00",
                  "startDate":"2026-11-07T18:00:00+00:00","doorTime":"18:00",
                  "location":{"@type":"Place","name":"The Garage"}}]
            </script>
        """.trimIndent()
        val eventPage = """
            <script type="application/ld+json">{"@type":"MusicEvent","name":"Messa","image":"https:\/\/alttickets-9a2.kxcdn.com\/static_alt_tickets\/images\/campaign\/400x400\/messa-7714307795.jpg"}</script>
            <div class="information venue"><article><p>You can buy a maximum of 10 tickets for this event</p></article></div>
        """.trimIndent()
        val fakeClient: HttpHandler = { request ->
            Response(OK).body(if (request.uri.path == "/venue/london/the-garage") listing else eventPage)
        }

        val gigs = TheGarageGigsSource(fakeClient).latestGigs()

        expectThat(gigs).containsExactly(
            Gig(
                GigId(theGarage.id, GigUrl("https://www.alttickets.com/messa-tickets/london-the-garage/2026-11-07-18-00")),
                GigTitle("Messa"),
                GigDate(2026, 11, 7),
                PosterUrl("https://alttickets-9a2.kxcdn.com/static_alt_tickets/images/campaign/400x400/messa-7714307795.jpg"),
                GigDescription(""),
            )
        )
    }

    @Test
    fun `fails when the listing carries no JSON-LD`() {
        val fakeClient: HttpHandler = { Response(OK).body("<html><body>Maintenance</body></html>") }

        assertFailsWith<IllegalStateException> { TheGarageGigsSource(fakeClient).latestGigs() }
    }

    private val source = AltTicketsVenueGigsSource(noHttp, url = "https://www.alttickets.com/venue/london/the-garage", venue = theGarage)

    @Test
    fun `takes the event's copy and bill, leaving out the ticket limit and the venue's information`() {
        val content = source.eventPageContent(pageOf(articleWith("""
            <p>You can buy a maximum of 10 tickets for this event</p>
            <h3>Event Information</h3><p>Formed in 2020, Allt is a progressive metalcore band.</p>
            <p>Now returning to Europe.</p><br>
            <div><h3 style="text-decoration:underline;">Performing Artist(s):</h3><p style="font-weight:bold;">ALLT</p><p>Avralize, Bite Down</p></div>
            <h3>Venue Address:</h3>The Garage<br/>20-22 Highbury Corner<br/>
            <h3>Venue Information:</h3>The Garage is at 20-22 Highbury Corner.
        """)))

        expectThat(content).isEqualTo("Formed in 2020, Allt is a progressive metalcore band. Now returning to Europe. ALLT Avralize, Bite Down")
    }

    @Test
    fun `takes copy written straight into the article rather than in a paragraph`() {
        val content = source.eventPageContent(pageOf(articleWith("""
            <h3>Event Information</h3>Doom from Padua.<br>
            <h3>Venue Address:</h3>The Garage
        """)))

        expectThat(content).isEqualTo("Doom from Padua.")
    }

    @Test
    fun `reads a page saying nothing about the gig as a blank description`() {
        val content = source.eventPageContent(pageOf(articleWith("""
            <p>You can buy a maximum of 10 tickets for this event</p>
            <h3>Venue Address:</h3>The Garage
        """)))

        expectThat(content).isEqualTo("")
    }

    @Test
    fun `reads a gig not on sale yet as a blank description, its page having no information block`() {
        val content = source.eventPageContent(pageOf("""<p><span class="glyphicon"></span> This event is not on sale yet.<br></p>"""))

        expectThat(content).isEqualTo("")
    }

    @Test
    fun `says nothing was found on a page that is neither`() {
        expectThat(source.eventPageContent(pageOf("<p>Something else entirely</p>"))).isNull()
    }

    private fun articleWith(html: String) = """<div class="information venue"><div class="container"><article>$html</article></div></div>"""
}
