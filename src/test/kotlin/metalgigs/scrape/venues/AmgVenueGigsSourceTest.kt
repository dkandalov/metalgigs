package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import metalgigs.validate.*
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

// Why this reading surface and paging: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
// Why a gig's url is its identity: docs/adr/0005-a-gig-is-identified-by-the-url-it-lives-at.md
// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
// Why the poster comes at full size: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
// Why a title is composed from the bill: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
class AmgVenueGigsSourceTest {

    @Test
    fun `extracts gig events from the O2 Forum Kentish Town events api, skipping ones with no ticket link`() {
        val events = assertScrapesGigs(
            source = O2ForumKentishTownGigsSource(cachedClient()),
            // 88 events are listed, but one happening today has closed its ticket sales and comes
            // back with no tickets at all, so it has no url to identify or link it by
            size = 87,
            first = Gig(
                GigId(o2ForumKentishTown.id, GigUrl("https://www.ticketmaster.co.uk/event/3E00636B9187395A")),
                GigTitle("The Mission: Gods Own Medicine 40th Anniversary Shows (4-DAY TICKET)"),
                GigDate(2026, 11, 4),
                PosterUrl("https://dynamicmedia.livenationinternational.com/v/t/j/f523afb1-3f0a-4c51-ab59-ec93e163d35f.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(o2ForumKentishTown.id, GigUrl("https://www.ticketmaster.co.uk/event/3E0065059E6A1198")),
                GigTitle("MASS OF THE FERMENTING DREGS"),
                GigDate(2027, 10, 14),
                amgDefaultPoster,
                GigDescription(""),
            ),
        )

        expectThat(events.count { it.posterUrl == amgDefaultPoster }).isEqualTo(4)
    }

    @Test
    fun `extracts gig events from the O2 Academy Brixton events api`() {
        val events = assertScrapesGigs(
            source = O2AcademyBrixtonGigsSource(cachedClient()),
            size = 72,
            first = Gig(
                GigId(o2AcademyBrixton.id, GigUrl("https://www.ticketmaster.co.uk/event/3E0064AB959719DE")),
                GigTitle("Tove Lo: ESTRUS TOUR"),
                GigDate(2026, 11, 7),
                PosterUrl("https://dynamicmedia.livenationinternational.com/o/d/i/b20837de-7ba0-4e7c-8ee5-a5db7dd58ce4.png"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(o2AcademyBrixton.id, GigUrl("https://www.ticketmaster.co.uk/event/3E006553EDAE6C67")),
                GigTitle("Angine de Poitrine"),
                GigDate(2027, 6, 30),
                PosterUrl("https://dynamicmedia.livenationinternational.com/p/k/z/08190ac5-e964-4c0e-84a4-81c2930effa2.jpg"),
                GigDescription(""),
            ),
        )

        expectThat(events.count { it.posterUrl == amgDefaultPoster }).isEqualTo(0)
        expectThat(events.count { !it.id.url.value.startsWith("https://www.ticketmaster.co.uk/") }).isEqualTo(0)
    }

    @Test
    fun `extracts gig events from both O2 Academy Islington rooms, which share one listing`() {
        val events = assertScrapesGigs(
            source = O2AcademyIslingtonGigsSource(cachedClient()),
            // the main room and the smaller Academy2 upstairs, listed together as the site does
            size = 66,
            first = Gig(
                GigId(o2AcademyIslington.id, GigUrl("https://www.ticketmaster.co.uk/event/3E006467A37039FD")),
                GigTitle("FOREVER NU - 25th anniversary of Toxicity & Iowa special! Chop Suey, Slip-Not, A7Xperience, Propa Roach"),
                GigDate(2026, 11, 6),
                PosterUrl("https://dynamicmedia.livenationinternational.com/z/j/n/0fc323e9-ee30-4527-951e-64f5e37eff3e.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(o2AcademyIslington.id, GigUrl("https://www.ticketmaster.co.uk/event/3E0064A10A70664C")),
                GigTitle("Kawehi"),
                GigDate(2027, 1, 8),
                PosterUrl("https://dynamicmedia.livenationinternational.com/q/r/h/65bd2b44-ffa3-46f8-8db6-e6eaa2c972c0.png"),
                GigDescription(""),
            ),
        )

        expectThat(events.count { it.posterUrl == amgDefaultPoster }).isEqualTo(7)
    }

    @Test
    fun `extracts gig events from the O2 Shepherd's Bush Empire events api`() {
        val events = assertScrapesGigs(
            source = O2ShepherdsBushEmpireGigsSource(cachedClient()),
            size = 77,
            first = Gig(
                GigId(o2ShepherdsBushEmpire.id, GigUrl("https://www.ticketmaster.co.uk/event/3E0064ADC4E850A1")),
                GigTitle("Deaf Havana: Fools & Worthless Liars + More / The Dangerous Summer"),
                GigDate(2026, 11, 6),
                PosterUrl("https://dynamicmedia.livenationinternational.com/h/w/u/b814ccbd-42db-41a5-9678-453d1b4b6ab9.png"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(o2ShepherdsBushEmpire.id, GigUrl("https://www.ticketmaster.co.uk/event/3E0064D0EB10676E")),
                GigTitle("Clearwater Creedence Revival: '60th Anniversary of C.C.R' Tour 2027"),
                GigDate(2027, 11, 27),
                PosterUrl("https://dynamicmedia.livenationinternational.com/e/o/k/21247638-dba8-45ed-9a31-5943a3bf78a6.png"),
                GigDescription(""),
            ),
        )

        expectThat(events.count { it.posterUrl == amgDefaultPoster }).isEqualTo(3)
        expectThat(events.count { !it.id.url.value.startsWith("https://www.ticketmaster.co.uk/event/") }).isEqualTo(0)
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

    private fun amgEvent(
        localizations: String,
        genres: String = "[]",
        lineup: String = "[]",
        image: String = "https://example.com/poster.jpg",
        tickets: String = """[{"ticketUrl": "https://www.ticketmaster.co.uk/event/ABC?utm_source=amg"}]""",
    ) = """
        {
          "name": "Wage War", "encodedName": "wage-war", "eventDate": "2027-01-16T00:00:00Z", "image": "$image",
          "tickets": $tickets,
          "localizations": $localizations, "genres": $genres, "lineup": $lineup
        }
    """.trimIndent()

    // ADÉLA at O2 Academy Brixton listed a Ticketmaster UK ticket and a Ticketmaster Metropolis one,
    // both url "", ahead of the branded one that carried the link - and, as of 2026-08-23, took the
    // whole venue's scrape down with it.
    @Test
    fun `takes an AMG gig's url from the first of its tickets that has one`() {
        val event = amgEvent(
            localizations = """[{"cultureName": "en-GB", "description": "<p>Copy.</p>"}]""",
            tickets = """[{"ticketUrl": ""}, {"ticketUrl": ""}, {"ticketUrl": "https://www.ticketmaster.co.uk/event/ABC?brand=o2academybrixton"}]""",
        )

        val gig = O2ForumKentishTownGigsSource(amgListingOf(event)).latestGigs().single()

        expectThat(gig.id.url).isEqualTo(GigUrl("https://www.ticketmaster.co.uk/event/ABC"))
    }

    // Beast In Black at O2 Forum Kentish Town listed Gigantic and Ticketmaster first in turn, moving
    // the gig to the other url every time the order changed.
    @Test
    fun `takes an AMG gig's url from its Gigantic ticket wherever that is listed`() {
        val gigantic = """{"ticketUrl": "https://www.gigantic.com/beast-in-black-tickets/london-o2-forum-kentish-town/2026-10-26-19-00"}"""
        val ticketmaster = """{"ticketUrl": "https://www.ticketmaster.co.uk/event/3E00634FC5C65A4D?brand=o2forum"}"""
        val urls = listOf("[$gigantic, $ticketmaster]", "[$ticketmaster, $gigantic]").map { tickets ->
            val event = amgEvent(localizations = """[{"cultureName": "en-GB", "description": "<p>Copy.</p>"}]""", tickets = tickets)
            O2ForumKentishTownGigsSource(amgListingOf(event)).latestGigs().single().id.url
        }

        expectThat(urls).isEqualTo(List(2) { GigUrl("https://www.gigantic.com/beast-in-black-tickets/london-o2-forum-kentish-town/2026-10-26-19-00") })
    }

    // IDER at O2 Academy Islington listed its O2 Priority presale ahead of its Ticketmaster link
    @Test
    fun `takes an AMG gig's url from its general sale rather than an O2 Priority presale`() {
        val event = amgEvent(
            localizations = """[{"cultureName": "en-GB", "description": "<p>Copy.</p>"}]""",
            tickets = """[{"ticketUrl": "https://www.o2.co.uk/priority/tickets/b27def23-a9ae-4d30-960f-66c49e178850"}, {"ticketUrl": "https://www.ticketmaster.co.uk/event/3E00655EC7BC8437?brand=o2academyislington"}]""",
        )

        val gig = O2ForumKentishTownGigsSource(amgListingOf(event)).latestGigs().single()

        expectThat(gig.id.url).isEqualTo(GigUrl("https://www.ticketmaster.co.uk/event/3E00655EC7BC8437"))
    }

    @Test
    fun `skips an AMG gig whose tickets all lack a link, as it does one listed with no tickets`() {
        val event = amgEvent(
            localizations = """[{"cultureName": "en-GB", "description": "<p>Copy.</p>"}]""",
            tickets = """[{"ticketUrl": ""}]""",
        )

        expectThat(O2ForumKentishTownGigsSource(amgListingOf(event)).latestGigs()).isEmpty()
    }

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

        // Read as of the day its soonest gig falls on, which is as near as recorded traffic gets to
        // the day it was captured: on any later one the recording has merely gone stale, and that is
        // nothing about these sources.
        val validation = validateGigs(scraped, scraped.values.flatten().minOf { it.date })

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

    @Test
    fun `titles an AMG gig with the acts its lineup bills under it`() {
        expectThat(titleOf("HEALTH", "HEALTH", "VOWWS")).isEqualTo(GigTitle("HEALTH / VOWWS"))
        expectThat(titleOf("Dayseeker: Creature In The Black Night Tour", "Dayseeker", "Northlane", "Siamese"))
            .isEqualTo(GigTitle("Dayseeker: Creature In The Black Night Tour / Northlane / Siamese"))
    }

    // The Wonder Stuff + Inspiral Carpets bills both acts in its name; East 17's name types the
    // guest with a curly apostrophe its lineup writes straight, and The Clone Roses' drops the full
    // stop its lineup gives The Smiths Ltd.
    @Test
    fun `leaves out an act the AMG gig's name already bills`() {
        expectThat(titleOf("The Wonder Stuff + Inspiral Carpets", "The Wonder Stuff", "Inspiral Carpets"))
            .isEqualTo(GigTitle("The Wonder Stuff + Inspiral Carpets"))
        expectThat(titleOf("Boys Are Back Again: East 17 with special guest Blazin’ Squad", "Boys Are Back Again", "East 17", "Blazin' Squad"))
            .isEqualTo(GigTitle("Boys Are Back Again: East 17 with special guest Blazin’ Squad"))
        expectThat(titleOf("The Clone Roses, Oasish, The Smiths Ltd", "The Clone Roses", "Oasish", "The Smiths Ltd."))
            .isEqualTo(GigTitle("The Clone Roses, Oasish, The Smiths Ltd"))
    }

    // Bury Tomorrow's lineup is five acts long, one of the six that run past four
    @Test
    fun `takes the AMG gig's name and three more acts from a longer lineup`() {
        expectThat(titleOf("Bury Tomorrow", "Bury Tomorrow", "Stick To Your Guns", "Windwaker", "Elwood Stray", "Fifth Act"))
            .isEqualTo(GigTitle("Bury Tomorrow / Stick To Your Guns / Windwaker / Elwood Stray"))
    }

    private fun titleOf(name: String, vararg acts: String): GigTitle {
        val lineup = acts.mapIndexed { i, act -> """{"id": "$i", "name": "$act"}""" }.joinToString(prefix = "[", postfix = "]")
        val event = amgEvent(localizations = """[{"cultureName": "en-GB", "description": "<p>Copy.</p>"}]""", lineup = lineup)
            .replace(""""name": "Wage War", "encodedName"""", """"name": "$name", "encodedName"""")
        return O2ForumKentishTownGigsSource(amgListingOf(event)).latestGigs().single().title
    }
}
