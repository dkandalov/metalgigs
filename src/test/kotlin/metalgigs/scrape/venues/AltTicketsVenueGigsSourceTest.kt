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
// Why a title is composed from the bill: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
class AltTicketsVenueGigsSourceTest {

    @Test
    fun `extracts gig events from The Garage's AltTickets listing`() {
        assertScrapesGigs(
            source = TheGarageGigsSource(cachedClient(), JoinedActs.keptWhole),
            size = 82,
            first = Gig(
                GigId(theGarage.id, GigUrl("https://www.alttickets.com/danko-jones-tickets/london-the-garage/2026-11-05-19-00")),
                GigTitle("Danko Jones / Los Pepes"),
                GigDate(2026, 11, 5),
                PosterUrl("https://alttickets-9a2.kxcdn.com/static_alt_tickets/images/campaign/400x400/danko_jones-7713508499.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(theGarage.id, GigUrl("https://www.alttickets.com/shonen-knife-tickets/london-the-garage/2027-06-19-19-00")),
                GigTitle("Shonen Knife"),
                GigDate(2027, 6, 19),
                PosterUrl("https://alttickets-9a2.kxcdn.com/static_alt_tickets/images/campaign/400x400/shonen_knife-7919715854.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )
    }

    @Test
    fun `extracts gig events from The Grace's AltTickets listing`() {
        assertScrapesGigs(
            source = TheGraceGigsSource(cachedClient(), JoinedActs.keptWhole),
            size = 68,
            first = Gig(
                GigId(theGrace.id, GigUrl("https://www.alttickets.com/lance-roark-tickets/london-the-grace/2026-11-05-19-30")),
                GigTitle("Lance Roark / Garden Birds"),
                GigDate(2026, 11, 5),
                PosterUrl("https://alttickets-9a2.kxcdn.com/static_alt_tickets/images/campaign/400x400/lance_roark-7745326167.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(theGrace.id, GigUrl("https://www.alttickets.com/izaro-tickets/london-the-grace/2027-11-13-19-00")),
                GigTitle("IZARO"),
                GigDate(2027, 11, 13),
                PosterUrl("https://alttickets-9a2.kxcdn.com/static_alt_tickets/images/campaign/400x400/izaro-7816914833.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
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

        val gigs = TheGarageGigsSource(fakeClient, JoinedActs.keptWhole).latestGigs()

        expectThat(gigs).containsExactly(
            Gig(
                GigId(theGarage.id, GigUrl("https://www.alttickets.com/messa-tickets/london-the-garage/2026-11-07-18-00")),
                GigTitle("Messa"),
                GigDate(2026, 11, 7),
                PosterUrl("https://alttickets-9a2.kxcdn.com/static_alt_tickets/images/campaign/400x400/messa-7714307795.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            )
        )
    }

    @Test
    fun `fails when the listing carries no JSON-LD`() {
        val fakeClient: HttpHandler = { Response(OK).body("<html><body>Maintenance</body></html>") }

        assertFailsWith<IllegalStateException> { TheGarageGigsSource(fakeClient, JoinedActs.keptWhole).latestGigs() }
    }

    private val source = AltTicketsVenueGigsSource(noHttp, JoinedActs.keptWhole, url = "https://www.alttickets.com/venue/london/the-garage", venue = theGarage)

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
    fun `leaves out a tracking pixel pasted in as the copy`() {
        val content = source.eventPageContent(pageOf(articleWith("""
            <h3>Event Information</h3><p>&lt;!-- Meta Pixel Code --&gt;</p>
            <p>&lt;script&gt; !function(f,b,e,v,n,t,s) {} &lt;/script&gt;</p>
            <p>&lt;!-- End Meta Pixel Code --&gt;</p><br>
            <div><h3>Performing Artist(s):</h3><p>Tom High</p></div>
        """)))

        expectThat(content).isEqualTo("Tom High")
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

    @Test
    fun `titles a gig with the acts the line under its heading bills`() {
        expectThat(billedTitle("Allt", "Supports : Avralize + Bite Down")).isEqualTo(GigTitle("Allt / Avralize / Bite Down"))
        expectThat(billedTitle("Desperate Journalist", "Support: Bloodworm / To The Lighthouse"))
            .isEqualTo(GigTitle("Desperate Journalist / Bloodworm / To The Lighthouse"))
        expectThat(billedTitle("Oh My God! It's The Church", "Opener: Jenny Hart")).isEqualTo(GigTitle("Oh My God! It's The Church / Jenny Hart"))
        expectThat(billedTitle("1000mods", "PLUS SPECIAL GUESTS - MARGARITA WITCH CULT / DEAD AIR"))
            .isEqualTo(GigTitle("1000mods / MARGARITA WITCH CULT / DEAD AIR"))
        expectThat(billedTitle("Dragged Into Sunlight", "Special Guests: Mizmor & This Gift"))
            .isEqualTo(GigTitle("Dragged Into Sunlight / Mizmor & This Gift"))
        expectThat(billedTitle("TEZ", "PLUS SPECIAL GUEST REEM MITTEN")).isEqualTo(GigTitle("TEZ / REEM MITTEN"))
        expectThat(billedTitle("Scarlett Woolfe", "+ Jonique + Misty Miller")).isEqualTo(GigTitle("Scarlett Woolfe / Jonique / Misty Miller"))
        expectThat(billedTitle("Al Cologne", "with Kitty Fitz")).isEqualTo(GigTitle("Al Cologne / Kitty Fitz"))
    }

    // As many bills join one band's own name with "&" or "and" as join two acts with it, so the
    // three listed on 2026-11-15 whose name carries one stay whole
    @Test
    fun `keeps a band whose name carries an ampersand or an and in one piece`() {
        expectThat(billedTitle("These Wicked Rivers", "Support : Beaux Gris Gris & The Magic Three"))
            .isEqualTo(GigTitle("These Wicked Rivers / Beaux Gris Gris & The Magic Three"))
        expectThat(billedTitle("Déjà Vega", "Support: Ruby Doomsday and The Mink"))
            .isEqualTo(GigTitle("Déjà Vega / Ruby Doomsday and The Mink"))
        expectThat(billedTitle("Haunt Me", "PLUS SPECIAL GUESTS   JESUS & THE ZEALOTS  / DEAD WRITERS"))
            .isEqualTo(GigTitle("Haunt Me / JESUS & THE ZEALOTS / DEAD WRITERS"))
    }

    @Test
    fun `keeps the venue's title where the line under its heading is a strapline`() {
        listOf(
            "Chicken Rhythms 35 Year Anniversary Tour",
            "50 YEARS OF DEFIANCE",
            "Present 'When can I see you?'",
            "Playing ‘In The City’ in full + a greatest hits set",
            "PLUS SPECIAL GUESTS",
        ).forEach { expectThat(billedTitle("Headliner", it)).isNull() }
    }

    @Test
    fun `takes the acts the line under its heading bills as a gig's lineup`() {
        val whetherTwoActs = JoinedActs { joined -> if (joined == "Mizmor & This Gift") joined.split(" & ") else listOf(joined) }
        fun lineupOf(line: String) = AltTicketsVenueGigsSource(noHttp, whetherTwoActs, url = "https://example.com/venue", venue = theGarage)
            .billedLineup(pageOf("""<h1 class="page-title">Headliner<br><small>$line</small><br></h1>"""))

        expectThat(lineupOf("Supports : Avralize + Bite Down")).isEqualTo(Lineup(listOf("Avralize", "Bite Down")))
        expectThat(lineupOf("Special Guests: Mizmor & This Gift")).isEqualTo(Lineup(listOf("Mizmor", "This Gift")))
        expectThat(lineupOf("Support : Beaux Gris Gris & The Magic Three")).isEqualTo(Lineup(listOf("Beaux Gris Gris & The Magic Three")))
        expectThat(lineupOf("50 YEARS OF DEFIANCE")).isEqualTo(Lineup.none)
    }

    private fun billedTitle(listedAs: String, line: String): GigTitle? {
        val page = pageOf("""<h1 class="page-title">$listedAs<br><small>$line</small><br></h1>""")
        return AltTicketsVenueGigsSource(noHttp, JoinedActs.keptWhole, url = "https://example.com/venue", venue = theGarage).billedTitle(page, GigTitle(listedAs))
    }
}
