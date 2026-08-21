package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AmgVenueGigsSourceTest {

    @Test
    fun `extracts gig events from the O2 Forum Kentish Town events api, skipping ones with no ticket link`() {
        val events = assertScrapesGigs(
            source = O2ForumKentishTownGigsSource(cachedClient()),
            // 88 events are listed, but one happening today has closed its ticket sales and comes
            // back with no tickets at all, so it has no url to identify or link it by
            size = 87,
            first = Gig(
                GigId(o2ForumKentishTown.id, "https://www.ticketmaster.co.uk/event/3E00648FA8A634C8"),
                GigTitle("Ronnie Wood & His Band featuring Imelda May"),
                GigDate(2026, 8, 21),
                PosterUrl("https://dynamicmedia.livenationinternational.com/g/v/y/79807d88-4cc2-4da8-acda-d434e0df08b2.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(o2ForumKentishTown.id, "https://www.ticketmaster.co.uk/event/3E0065059E6A1198"),
                GigTitle("MASS OF THE FERMENTING DREGS"),
                GigDate(2027, 10, 14),
                PosterUrl("https://dynamicmedia.livenationinternational.com/t/a/f/03bb4ec9-ed69-4d30-b4d4-1e516b000455.jpg"),
                GigDescription(""),
            ),
        )

        expectThat(events.count { it.posterUrl == amgDefaultPoster }).isEqualTo(3)
    }

    @Test
    fun `extracts gig events from the O2 Academy Brixton events api`() {
        val events = assertScrapesGigs(
            source = O2AcademyBrixtonGigsSource(cachedClient()),
            size = 67,
            first = Gig(
                GigId(o2AcademyBrixton.id, "https://www.ticketmaster.co.uk/event/3E006464ACEB4803"),
                GigTitle("Primus"),
                GigDate(2026, 8, 19),
                amgDefaultPoster,
                GigDescription(""),
            ),
            last = Gig(
                GigId(o2AcademyBrixton.id, "https://www.ticketmaster.co.uk/event/3E006452FC929180"),
                GigTitle("Loreen: THE WILDFIRE TOUR"),
                GigDate(2026, 9, 26),
                PosterUrl("https://dynamicmedia.livenationinternational.com/i/l/u/977ca756-1a25-4148-b46a-e2667effd53f.jpg"),
                GigDescription(""),
            ),
        )

        expectThat(events.count { it.posterUrl == amgDefaultPoster }).isEqualTo(1)
        expectThat(events.count { !it.id.url.startsWith("https://www.ticketmaster.co.uk/") }).isEqualTo(3)
    }

    @Test
    fun `extracts gig events from both O2 Academy Islington rooms, which share one listing`() {
        val events = assertScrapesGigs(
            source = O2AcademyIslingtonGigsSource(cachedClient()),
            // the main room and the smaller Academy2 upstairs, listed together as the site does
            size = 83,
            first = Gig(
                GigId(o2AcademyIslington.id, "https://www.ticketmaster.co.uk/event/3E00646A8FB52ACA"),
                GigTitle("OCT (On Company Time) UK Tour"),
                GigDate(2026, 8, 29),
                PosterUrl("https://dynamicmedia.livenationinternational.com/v/v/w/023063cb-a764-4f67-9d96-075a1bd3d454.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(o2AcademyIslington.id, "https://www.ticketmaster.co.uk/event/3E0064F5350835B8"),
                GigTitle("The Reggae Orchestra comes to London"),
                GigDate(2027, 5, 1),
                PosterUrl("https://dynamicmedia.livenationinternational.com/m/a/b/e51bb674-c586-4164-9477-c725574f74ca.jpg"),
                GigDescription(""),
            ),
        )

        expectThat(events.count { it.posterUrl == amgDefaultPoster }).isEqualTo(4)
    }

    @Test
    fun `extracts gig events from the O2 Shepherd's Bush Empire events api`() {
        val events = assertScrapesGigs(
            source = O2ShepherdsBushEmpireGigsSource(cachedClient()),
            size = 95,
            first = Gig(
                GigId(o2ShepherdsBushEmpire.id, "https://www.ticketmaster.co.uk/event/3E0064AFD611527C"),
                GigTitle("AFI"),
                GigDate(2026, 8, 20),
                PosterUrl("https://dynamicmedia.livenationinternational.com/s/x/l/353f9994-6437-4ccd-b401-a48c39f23a4b.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(o2ShepherdsBushEmpire.id, "https://www.ticketmaster.co.uk/event/3E0064D0EB10676E"),
                GigTitle("Clearwater Creedence Revival: '60th Anniversary of C.C.R' Tour 2027"),
                GigDate(2027, 11, 27),
                PosterUrl("https://dynamicmedia.livenationinternational.com/e/o/k/21247638-dba8-45ed-9a31-5943a3bf78a6.png"),
                GigDescription(""),
            ),
        )

        expectThat(events.count { it.posterUrl == amgDefaultPoster }).isEqualTo(2)
        expectThat(events.count { !it.id.url.startsWith("https://www.ticketmaster.co.uk/event/") }).isEqualTo(1)
    }

    private val amgDefaultPoster = PosterUrl("https://networksites.livenationinternational.com/networksites/krfjkan0/defualt-event-image-amg.jpg")

    @Test
    fun `takes a posterless AMG gig's image from the page the event renders`() {
        val event = amgEvent(image = "", localizations = """[{"cultureName": "en-GB", "description": ""}]""", genres = """[{"name": "Rock"}]""", lineup = """[{"id": "497715", "name": "Wage War", "type": "headline"}]""")
        val pageUrls = mutableListOf<String>()
        val client: HttpHandler = { request ->
            if (request.uri.path.startsWith("/api/")) Response(OK).body("""{"documents":[$event]}""")
            else {
                pageUrls += request.uri.toString()
                Response(OK).body("""<img sizes="100vw" src="$amgDefaultPoster?format=webp&amp;width=3840&amp;quality=75">""")
            }
        }

        val gig = O2ForumKentishTownGigsSource(client).latestGigs().single()

        expectThat(pageUrls).containsExactly("https://www.academymusicgroup.com/o2forumkentishtown/events/wage-war-tickets-ae497715")
        expectThat(gig.posterUrl).isEqualTo(amgDefaultPoster)
    }

    @Test
    fun `does not fetch an event page for an AMG gig whose listing carries an image`() {
        val event = amgEvent(localizations = """[{"cultureName": "en-GB", "description": "<p>Copy.</p>"}]""")
        val client: HttpHandler = { request ->
            if (request.uri.path.startsWith("/api/")) Response(OK).body("""{"documents":[$event]}""")
            else error("Fetched ${request.uri} for a gig whose listing already carries an image")
        }

        expectThat(O2ForumKentishTownGigsSource(client).latestGigs().single().posterUrl)
            .isEqualTo(PosterUrl("https://example.com/poster.jpg"))
    }

    // None of the 173 events the four venues list as of 2026-08-18 lacks a lineup, so this fails a
    // scrape rather than modelling a posterless outcome for it.
    @Test
    fun `fails a posterless AMG gig with no lineup to build its event page url from`() {
        val event = amgEvent(image = "", localizations = """[{"cultureName": "en-GB", "description": "<p>Copy.</p>"}]""")

        val error = assertFailsWith<IllegalStateException> {
            O2ForumKentishTownGigsSource(amgListingOf(event)).latestGigs()
        }

        expectThat(error.message!!.contains("Wage War")).isTrue()
    }

    // An AMG event's description comes out of the listing api rather than an event page, so these
    // give the source one event's json instead of one page's markup. The fields are named and
    // shaped as the real api writes them, down to the html the copy is stored as.
    private fun amgListingOf(event: String): HttpHandler = { Response(OK).body("""{"documents":[$event]}""") }

    private fun amgEvent(localizations: String, genres: String = "[]", lineup: String = "[]", image: String = "https://example.com/poster.jpg") = """
        {
          "name": "Wage War", "encodedName": "wage-war", "eventDate": "2027-01-16T00:00:00Z", "image": "$image",
          "tickets": [{"ticketUrl": "https://www.ticketmaster.co.uk/event/ABC?utm_source=amg"}],
          "localizations": $localizations, "genres": $genres, "lineup": $lineup
        }
    """.trimIndent()

    @Test
    fun `takes an AMG gig's description from the promoter's copy in the listing api`() {
        val event = amgEvent(
            localizations = """[{"cultureName": "en-GB", "description": "<p>Metalcore from Florida.</p>\r\n<p>Support TBA.&nbsp;</p>"}]""",
            genres = """[{"name": "Hard Rock And Metal"}]""",
            lineup = """[{"id": "497715", "name": "Wage War", "type": "headline"}]""",
        )

        val gig = O2ForumKentishTownGigsSource(amgListingOf(event)).latestGigs().single()

        expectThat(gig.description).isEqualTo(GigDescription("Metalcore from Florida. Support TBA."))
    }

    // About one AMG event in ten has no copy written for it, and its acts and genres are all the
    // api says about what kind of gig it is.
    @Test
    fun `describes an AMG gig with no copy by its acts and genres`() {
        val event = amgEvent(
            localizations = """[{"cultureName": "en-GB", "description": ""}]""",
            genres = """[{"name": "Hard Rock And Metal"}]""",
            lineup = """[{"id": "497715", "name": "Wage War", "type": "headline"}, {"id": "497716", "name": "Invent Animate", "type": "support"}]""",
        )

        val gig = O2ForumKentishTownGigsSource(amgListingOf(event)).latestGigs().single()

        expectThat(gig.description).isEqualTo(GigDescription("Lineup: Wage War, Invent Animate. Genre: Hard Rock And Metal."))
    }

    // Verbatim from a real listing, where the same notice stood in for the copy of cancelled shows
    // at three of the four venues.
    @Test
    fun `describes an AMG gig whose copy is the cancellation notice by its acts and genres`() {
        val notice = "<p>Sorry, this show has been cancelled and there aren't any plans to reschedule. " +
            "You'll be able to get a full refund from wherever you bought your tickets, and your " +
            "ticket agent will be in touch to tell you more.</p>"
        val event = amgEvent(
            localizations = """[{"cultureName": "en-GB", "description": "$notice"}]""",
            genres = """[{"name": "Hard Rock And Metal"}]""",
            lineup = """[{"id": "497715", "name": "Wage War", "type": "headline"}]""",
        )

        val gig = O2ForumKentishTownGigsSource(amgListingOf(event)).latestGigs().single()

        expectThat(gig.description).isEqualTo(GigDescription("Lineup: Wage War. Genre: Hard Rock And Metal."))
    }

    // Every AMG gig observed while their descriptions came from the ticketing page held that page's
    // bot wall rather than any copy - 334 of the 335 in the log as of 2026-08-17 - and no run said
    // so, because the check of the day looked only at gigs that had changed since the last one.
    @Test
    fun `no gig the AMG sources list fails validation`() {
        val client = cachedClient()
        val scraped = listOf(
            O2ForumKentishTownGigsSource(client),
            O2AcademyBrixtonGigsSource(client),
            O2AcademyIslingtonGigsSource(client),
            O2ShepherdsBushEmpireGigsSource(client),
        ).associate { it.venue.id to it.latestGigs() }

        val validation = validateGigs(scraped)

        expectThat(
            validation.reports.flatMap { report ->
                report.problems.map { "${it.venueId}: ${it.detail} (${it.gigs.size} gig(s))" }
            }
        ).isEmpty()
    }

    @Test
    fun `describes an AMG gig with neither copy nor a lineup by its genres alone`() {
        val event = amgEvent(
            localizations = """[{"cultureName": "en-GB", "description": ""}]""",
            genres = """[{"name": "Other"}]""",
        )

        val gig = O2ForumKentishTownGigsSource(amgListingOf(event)).latestGigs().single()

        expectThat(gig.description).isEqualTo(GigDescription("Genre: Other."))
    }
}
