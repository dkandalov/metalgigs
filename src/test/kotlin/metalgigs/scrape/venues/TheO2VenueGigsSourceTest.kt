package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import kotlin.test.Test

class TheO2VenueGigsSourceTest {

    @Test
    fun `extracts gig events from indigo at The O2, paging past the listing's first batch`() {
        val events = assertScrapesGigs(
            source = IndigoAtTheO2GigsSource(cachedClient()),
            size = 53,
            first = Gig(
                GigId(indigoAtTheO2.id, "https://www.theo2.co.uk/events/detail/timaya"),
                GigTitle("TIMAYA"),
                GigDate(2026, 8, 22),
                PosterUrl("https://www.theo2.co.uk/assets/img/1080X1080-a8eecfe5d3.png"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(indigoAtTheO2.id, "https://www.theo2.co.uk/events/detail/bat-50th-anniversary-concert"),
                GigTitle("Fuel Injected Magic! 50th Anniversary Concert"),
                GigDate(2027, 10, 30),
                PosterUrl("https://www.theo2.co.uk/assets/img/Steve-Steinmans-Fuel-Injected-Magic-50th-square-Post3er-jpg-48832cc6ba.jpg"),
                GigDescription(""),
            ),
        )

        // the venue's own page renders 24 and says nothing about the rest, so a size well past that
        // is what stands between the paging breaking and a listing quietly losing two thirds of itself
        expectThat(events.map { it.id.url }.distinct()).hasSize(53)
        expectThat(events.filter { it.date.year == 2027 }).hasSize(11)
    }

    @Test
    fun `extracts gig events from The O2 Arena, paging past the listing's first batch`() {
        val events = assertScrapesGigs(
            source = TheO2ArenaGigsSource(cachedClient()),
            size = 86,
            first = Gig(
                GigId(theO2Arena.id, "https://www.theo2.co.uk/events/detail/ariana-grande-2026"),
                GigTitle("Ariana Grande"),
                GigDate(2026, 8, 19),
                PosterUrl("https://www.theo2.co.uk/assets/img/Static_TM-ArtistImage_2426x1365_ArianaGrande_2026_Photo-copy-square-b4f4051fe8.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theO2Arena.id, "https://www.theo2.co.uk/events/detail/dungeons-dragons-fan-expo-london-2027"),
                GigTitle("Dungeons & Dragons Fan Expo: London 2027 | Rescheduled"),
                GigDate(2027, 9, 5),
                PosterUrl("https://www.theo2.co.uk/assets/img/DD_1080-x-1080-Press-shot-32a180fd24.jpg"),
                GigDescription(""),
            ),
        )

        expectThat(events.map { it.id.url }.distinct()).hasSize(86)
        // the arena writes "June" and "July" in full where every other month is three letters, so
        // both spellings have to be read - indigo's listing happens to carry neither
        expectThat(events.map { it.date }.filter { it.monthValue in 6..7 }).hasSize(2)
    }

    // The second card on the listing is a run of dates, "23 Aug - 19 Dec 2026", and the year is
    // written only on the end of it.
    @Test
    fun `takes a The O2 start date from a range that writes its year only once`() {
        val html = """
            <div class="date divider-date">
              <span class="m-date__rangeFirst"><span class="m-date__day">28 </span><span class="m-date__month">Dec </span></span>
              <span class="m-date__separator"> - </span>
              <span class="m-date__rangeLast"><span class="m-date__day">3 </span><span class="m-date__month">Jan </span><span class="m-date__year"> 2027</span></span>
            </div>
        """.trimIndent()

        val date = TheO2VenueGigsSource(noHttp, theO2VenueId = 2, venue = indigoAtTheO2).startDateOf(pageOf(html).select(".date").first()!!)

        expectThat(date).isEqualTo(GigDate(2026, 12, 28))
    }

    @Test
    fun `takes a The O2 start date from a single day that writes its own year`() {
        val html = """
            <div class="date divider-date">
              <span class="m-date__singleDate"><span class="m-date__day">22 </span><span class="m-date__month">Aug </span><span class="m-date__year"> 2026</span></span>
            </div>
        """.trimIndent()

        val date = TheO2VenueGigsSource(noHttp, theO2VenueId = 2, venue = indigoAtTheO2).startDateOf(pageOf(html).select(".date").first()!!)

        expectThat(date).isEqualTo(GigDate(2026, 8, 22))
    }

    // the sign-up block and terms are verbatim from a real event page, where the whole page's text
    // carries the site nav and both of these into every gig
    @Test
    fun `scopes The O2's page text to the promoter's copy`() {
        val html = """
            <nav><a>Events</a><a>Visit us</a></nav>
            <div class="content_item textarea">
              <h2>Event Details</h2>
              <div class="event_description expandable"><p>German heavy metal pioneers ACCEPT celebrate their 50th anniversary.</p></div>
            </div>
            <div class="edp-signup"><h2>Sign up for updates and pre-sales</h2><p>We recommend signing up for alerts.</p></div>
            <div class="terms_conditions holder"><h3>Terms of entry</h3><p>Unless otherwise stated, all indigo shows are 3+.</p></div>
        """.trimIndent()

        val pageText = TheO2VenueGigsSource(noHttp, theO2VenueId = 2, venue = indigoAtTheO2).eventPageContent(pageOf(html))!!

        expectThat(pageText).isEqualTo("German heavy metal pioneers ACCEPT celebrate their 50th anniversary.")
    }
}
