package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.time.LocalDate
import kotlin.test.Test

class DiceVenueGigsSourceTest {

    @Test
    fun `extracts gig events from Blondies Brewery Taproom's dice_fm venue page`() {
        assertScrapesGigs(
            source = BlondiesBreweryTaproomGigsSource(cachedClient()),
            size = 9,
            first = Gig(
                GigId(blondiesBreweryTaproom.id, "https://dice.fm/event/2wqb7p-its-never-over-jeff-buckley-screening-12th-aug-blondies-brewery-london-tickets"),
                GigTitle("It's Never Over, Jeff Buckley > Screening"),
                LocalDate.of(2026, 8, 12),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-08-03/6088fc1d-076f-4946-b1d6-342519c36355.jpg?rect=0%2C49%2C2159%2C2159"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(blondiesBreweryTaproom.id, "https://dice.fm/event/8eq9dw-forlorn-birdwitch-27th-nov-blondies-brewery-london-tickets"),
                GigTitle("FORLORN / BIRDWITCH"),
                LocalDate.of(2026, 11, 27),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-07-13/d2e1f34c-9f57-4a47-811c-5e6d4efbc40a.jpg?rect=0%2C135%2C1080%2C1080"),
                GigDescription(""),
            ),
            urlPrefix = "https://dice.fm/event/",
        )
    }

    @Test
    fun `extracts gig events from Blondies Bar's dice_fm venue page`() {
        assertScrapesGigs(
            source = BlondiesBarGigsSource(cachedClient()),
            size = 26,
            first = Gig(
                GigId(blondiesBar.id, "https://dice.fm/event/av57g7-midweek-mayhem-4-pints-all-night-12th-aug-blondies-london-tickets"),
                GigTitle("Midweek Mayhem – £4 Pints All Night"),
                LocalDate.of(2026, 8, 12),
                PosterUrl("https://dice-media.imgix.net/attachments/2025-07-23/03c4258d-44cc-4c61-8612-5d5495f6684b.jpg?rect=0%2C0%2C4385%2C4385"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(blondiesBar.id, "https://dice.fm/event/bboxdm-1986-support-5th-dec-blondies-london-tickets"),
                GigTitle("1986 + Support"),
                LocalDate.of(2026, 12, 5),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-04-27/4c005268-bc5b-43c7-a69f-8117623d0232.jpg?rect=0%2C0%2C2048%2C2048"),
                GigDescription(""),
            ),
            urlPrefix = "https://dice.fm/event/",
        )
    }

    @Test
    fun `extracts gig events from Helgi's dice_fm venue page`() {
        assertScrapesGigs(
            source = HelgisGigsSource(cachedClient()),
            size = 15,
            first = Gig(
                GigId(helgis.id, "https://dice.fm/event/avrpa2-sceptocrypt-in-gods-way-cariad-14th-aug-helgis-london-tickets"),
                GigTitle("Sceptocrypt + In Gods Way + Cariad"),
                LocalDate.of(2026, 8, 14),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-08-09/bcabb7e3-0777-4c15-929c-9192d05503fb.jpg?rect=0%2C32%2C1187%2C1187"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(helgis.id, "https://dice.fm/event/xedvra-holocaust-hyena-14th-nov-helgis-london-tickets"),
                GigTitle("HOLOCAUST + HYENA"),
                LocalDate.of(2026, 11, 14),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-04-07/cdca232f-2df2-41a6-a2b1-cdaa5c827aa3.jpg?rect=0%2C135%2C1080%2C1080"),
                GigDescription(""),
            ),
            urlPrefix = "https://dice.fm/event/",
        )
    }

    @Test
    fun `extracts gig events from Barfly's dice_fm venue page`() {
        assertScrapesGigs(
            source = BarflyGigsSource(cachedClient()),
            size = 24,
            first = Gig(
                GigId(barfly.id, "https://dice.fm/event/xe37pm-propaganda-indie-club-night-at-barfly-15th-aug-barfly-camden-london-tickets"),
                GigTitle("Propaganda - Indie Club Night at Barfly!"),
                LocalDate.of(2026, 8, 15),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-08-06/1c3136b1-128c-4ca2-ac03-809114ab7663.jpg?rect=0%2C0%2C1080%2C1080"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(barfly.id, "https://dice.fm/event/k6lw79-forever-never-13th-feb-barfly-camden-london-tickets"),
                GigTitle("Forever Never"),
                LocalDate.of(2027, 2, 13),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-06-16/6f0ca902-24a7-4421-a288-f70e55030959.jpg?rect=0%2C0%2C1400%2C1400"),
                GigDescription(""),
            ),
            urlPrefix = "https://dice.fm/event/",
        )
    }

    // dice.fm venues (Blondies Brewery Taproom, Blondies Bar, Helgi's, Barfly) render almost nothing
    // server-side to select from - the real description is nested two JSON parses deep inside
    // __NEXT_DATA__ (itself containing a JSON-encoded string), alongside plenty of sitewide data
    // (i18n strings, nav) this fixture only trims down, not invents
    @Test
    fun `scopes dice-fm page text to the event's own about-description, ignoring the surrounding JSON`() {
        val html = """
            <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"otherStuff":"ignore me","initialState":"{\"event\":{\"event\":{\"about\":{\"description\":\"Doom metal night!\"}}}}"}}}
            </script>
        """.trimIndent()

        val pageText = diceEventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("ignore me")).isEqualTo(false)
    }
}
