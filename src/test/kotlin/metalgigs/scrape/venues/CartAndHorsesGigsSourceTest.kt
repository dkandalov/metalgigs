package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test

// Why the calendar and the endpoint behind its More: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
// Why the poster is the page's: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
// Why the date is read this way: docs/adr/0010-a-date-is-read-per-venue-and-a-missing-year-is-inferred.md
class CartAndHorsesGigsSourceTest {

    // 10 entries on the page and two batches of 20 and 12 behind its More, recorded 2026-11-26
    @Test
    fun `extracts gig events from the calendar and the batches behind its More`() {
        val events = assertScrapesGigs(
            source = CartAndHorsesGigsSource(cachedClient(), JoinedActs.keptWhole, year = 2026),
            size = 42,
            first = Gig(
                GigId(cartAndHorses.id, GigUrl("https://www.cartandhorses.london/news-offers-events/569712-motorwrecked-alice-in-chains-uk-darkmoore/")),
                GigTitle("MOTORWRECKED + ALICE IN CHAINS UK + DARKMOORE"),
                GigDate(2026, 11, 27),
                PosterUrl("https://www.useyourlocal.com/imgs/pub_events/default/270126-043912_smelllike.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(cartAndHorses.id, GigUrl("https://www.cartandhorses.london/news-offers-events/601294-legend-of-valley-doom-live-in-london/")),
                GigTitle("Legend of Valley Doom – Live in London"),
                GigDate(2027, 10, 16),
                PosterUrl("https://www.useyourlocal.com/imgs/pub_events/default/270126-043912_smelllike.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )

        expectThat(events.take(3).map { it.date })
            .containsExactly(GigDate(2026, 11, 27), GigDate(2026, 11, 28), GigDate(2026, 11, 29))
        expectThat(events.map { it.id }.toSet().size).isEqualTo(42)

        val titles = events.map { it.title.value }
        listOf("VULGARIS", "MEGADETH UK", "ATOMIC ROOSTER", "HELLBENT FOREVER", "OTTO JUNIOR")
            .forEach { band -> expectThat(titles.any { it.contains(band) }).isTrue() }
    }

    @Test
    fun `follows the calendar's More from the offset it names, rolling the year over into January`() {
        val asked = mutableListOf<String>()
        val client = calendarClient(
            listing = entry("Dec", "20", "/news-offers-events/1-dec-gig/", "DEC GIG") + moreFrom(offset = 1),
            batches = mapOf(
                1 to entry("Jan", "10", "/news-offers-events/2-jan-gig/", "JAN GIG") + entry("Feb", "01", "/news-offers-events/3-feb-gig/", "FEB GIG"),
            ),
            asked,
        )

        val events = CartAndHorsesGigsSource(client, JoinedActs.keptWhole, year = 2026).latestGigs()

        expectThat(events.map { it.title }).containsExactly(GigTitle("DEC GIG"), GigTitle("JAN GIG"), GigTitle("FEB GIG"))
        expectThat(events.map { it.date }).containsExactly(GigDate(2026, 12, 20), GigDate(2027, 1, 10), GigDate(2027, 2, 1))
        expectThat(asked.filter { "/next/" in it }).containsExactly(
            "https://www.cartandhorses.london/news-offers-events/next/events/1/122026/?theme_id=7&option_id=1",
            "https://www.cartandhorses.london/news-offers-events/next/events/3/122026/?theme_id=7&option_id=1",
        )
    }

    // British Lion's six nights, listed on 2026-11-26 under 13 December alone
    @Test
    fun `dates a run of nights by its first, leaving when it ends out of the title`() {
        val client = calendarClient(
            listing = entry("Dec", "13", "/news-offers-events/1-residency/", """RESIDENCY<br /><small class="until">Until 18th December 2026</small>""") + moreFrom(offset = 1),
        )

        val events = CartAndHorsesGigsSource(client, JoinedActs.keptWhole, year = 2026).latestGigs()

        expectThat(events.map { it.title to it.date }).containsExactly(GigTitle("RESIDENCY") to GigDate(2026, 12, 13))
    }

    @Test
    fun `takes a gig's poster from the upload its own page links`() {
        val client = calendarClient(listing = entry("Dec", "20", "/news-offers-events/1-dec-gig/", "DEC GIG") + moreFrom(offset = 1))

        val events = CartAndHorsesGigsSource(client, JoinedActs.keptWhole, year = 2026).latestGigs()

        expectThat(events.map { it.posterUrl }).containsExactly(PosterUrl("https://www.useyourlocal.com/imgs/pub_events/default/poster.jpg"))
    }

    @Test
    fun `fails on a calendar with no More under it rather than reading the page as the whole of it`() {
        val client = calendarClient(listing = entry("Dec", "20", "/news-offers-events/1-dec-gig/", "DEC GIG"))

        expectThrows<IllegalStateException> { CartAndHorsesGigsSource(client, JoinedActs.keptWhole, year = 2026).latestGigs() }
    }

    @Test
    fun `scopes Cart & Horses page text to the page header and content, ignoring nav and footer`() {
        val html = """
            <nav><a>Sign up</a><a>Food & Drink</a></nav>
            <header class="page_header"><h1>Doom Night</h1></header>
            <div class="page_content_inner"><p>Doom metal night!</p></div>
            <footer>Opening times Mon: 12:00 - 00:00 Cart & Horses 1 Maryland Point</footer>
        """.trimIndent()

        val pageText = CartAndHorsesGigsSource(noHttp, JoinedActs.keptWhole, year = 2026).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom Night")).isTrue()
        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Food & Drink")).isEqualTo(false)
        expectThat(pageText.contains("Opening times")).isEqualTo(false)
    }

    @Test
    fun `takes a gig's lineup from a heading that bills its acts`() {
        val gigs = CartAndHorsesGigsSource(cachedClient(), JoinedActs.keptWhole, year = 2026).latestGigs()

        expectThat(gigs.first { it.title == GigTitle("VULGARIS + THE SLAUGHTERING + AHREMAN + DEATHSCRIPPTED") }.lineup)
            .isEqualTo(Lineup(listOf("VULGARIS", "THE SLAUGHTERING", "AHREMAN", "DEATHSCRIPPTED")))
    }

    // The listing page, the batches its More asks for by offset - an offset with no batch answering
    // an empty one, as the site does past the calendar's end - and one event page for every gig.
    private fun calendarClient(listing: String, batches: Map<Int, String> = emptyMap(), asked: MutableList<String> = mutableListOf()): HttpHandler = { request ->
        asked += request.uri.toString()
        val path = request.uri.path
        Response(OK).body(
            when {
                "/next/events/" in path -> batchOf(batches[path.substringAfter("/next/events/").substringBefore('/').toInt()].orEmpty())
                path == "/news-offers-events/" -> """<body data-theme-id="7"><div class="ajax_append_events">$listing</div></body>"""
                else -> """
                    <a class="hb-single" href="https://www.useyourlocal.com/imgs/pub_events/default/poster.jpg"><img src="https://www.useyourlocal.com/imgs/pub_events/730w/poster.jpg"></a>
                    <div class="page_content_inner">Doom night, doors 7pm.</div>
                """.trimIndent()
            }
        )
    }

    private fun entry(month: String, day: String, href: String, heading: String) = """
        <article class="calendar-list">
            <div class="calendar-list__date-wrap"><div class="calendar-list__date">
                <div class="calendar-list__month">$month</div>
                <div class="calendar-list__day">$day</div>
            </div></div>
            <h1 class="calendar-list__title"><a href="$href" class="calendar-list__title-text">$heading</a></h1>
        </article>
    """.trimIndent()

    private fun moreFrom(offset: Int) =
        """<div role="button" class="more_bar ajax_more_by_month" data-type="events" data-id="$offset" data-last-date="122026">More ></div>"""

    private fun batchOf(entries: String) =
        """{"ajax_count":{"events":${Regex("<article").findAll(entries).count()}},"view":${jsonString(entries)}}"""

    private fun jsonString(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
