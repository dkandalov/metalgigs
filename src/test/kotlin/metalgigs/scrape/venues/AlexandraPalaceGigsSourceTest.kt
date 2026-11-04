package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test

// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
// Why the date is read this way: docs/adr/0010-a-date-is-read-per-venue-and-a-missing-year-is-inferred.md
class AlexandraPalaceGigsSourceTest {

    @Test
    fun `extracts gig events from Alexandra Palace's what's on page`() {
        val events = assertScrapesGigs(
            source = AlexandraPalaceGigsSource(cachedClient()),
            size = 41,
            first = Gig(
                GigId(alexandraPalace.id, GigUrl("https://www.alexandrapalace.com/whats-on/upside-down-london/")),
                // trailing   (narrow no-break space), not a plain space - it's what the
                // page's own title text actually contains, confirmed character-by-character
                // against a failed run before this literal was written
                GigTitle("Upside Down London"),
                GigDate(2026, 8, 1),
                PosterUrl("https://www.alexandrapalace.com/wp-content/uploads/2026/05/pl-udl-approved-media-assets-14-of-17-marked-2048x1536.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(alexandraPalace.id, GigUrl("https://www.alexandrapalace.com/whats-on/kaleidoscope-festival-2/")),
                GigTitle("Kaleidoscope Festival"),
                GigDate(2027, 7, 10),
                PosterUrl("https://www.alexandrapalace.com/wp-content/uploads/2026/07/Kaleidescope-11.07.26-www.harbinson.uk-7159-2048x1366.jpg"),
                GigDescription(""),
            ),
        )
    }

    @Test
    fun `resolves the start date of a range, including one that crosses a calendar year`() {
        fun eventPage(dates: String) = """
            <div class="event_card_wrapper">
                <div class="event_img proportional_container"><img src="https://example.com/poster.jpg"></div>
                <header><p class="dates uc"><strong>$dates</strong></p>
                <a href="https://www.alexandrapalace.com/whats-on/gig" class="event_target"><h3>Gig</h3></a></header>
            </div>
            <!-- the same body answers the event-page request, which now has to yield a description -->
            <div class="ap_text_block">An evening of something.</div>
        """.trimIndent()

        fun startDateOf(dates: String): GigDate {
            val fakeClient: HttpHandler = { Response(OK).body(eventPage(dates)) }
            return AlexandraPalaceGigsSource(fakeClient).latestGigs().single().date
        }

        expectThat(startDateOf("21 Aug 2026")).isEqualTo(GigDate(2026, 8, 21))
        // same month range - the year and month are only written once, on the end day
        expectThat(startDateOf("1 - 9 Aug 2026")).isEqualTo(GigDate(2026, 8, 1))
        // cross-month range within one year - both start and end take the written year
        expectThat(startDateOf("19 Sep - 5 Dec 2026")).isEqualTo(GigDate(2026, 9, 19))
        // cross-month range crossing new year's day - the written year belongs to the end date
        // (Jan 2027), so the start date (Dec) must roll back to the year before it
        expectThat(startDateOf("11 Dec - 3 Jan 2027")).isEqualTo(GigDate(2026, 12, 11))
    }

    // two different kinds of boilerplate reach the whole page: the sitewide nav ("Summer Season",
    // "Food And Drink") outside #event_content, and a sidebar of generic quick-link buttons ("Buy
    // Tickets", "FAQs", ...) *inside* it - the second one only turned up against the real site,
    // after #event_content alone looked like enough of a fix
    @Test
    fun `scopes Alexandra Palace page text to the description and key-information accordion`() {
        val html = """
            <nav><li>Summer Season</li><li>Food And Drink</li></nav>
            <div id="event_content">
                <div class="event_sidebar"><ul class="event_buttons"><li>Buy Tickets</li><li>FAQs</li></ul></div>
                <div class="ap_text_block"><p>Doom metal night!</p></div>
                <div id="key-information"><h3>Key information</h3><p>Support from Kings of Thrash.</p></div>
            </div>
        """.trimIndent()

        val pageText = AlexandraPalaceGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Kings of Thrash")).isTrue()
        expectThat(pageText.contains("Summer Season")).isEqualTo(false)
        expectThat(pageText.contains("Buy Tickets")).isEqualTo(false)
    }

    @Test
    fun `titles a gig with the acts its subheader bills`() {
        expectThat(billedTitle("Trivium", "Plus Special Guests: In Flames + Paleface Swiss + VOLA"))
            .isEqualTo(GigTitle("Trivium / In Flames / Paleface Swiss / VOLA"))
        expectThat(billedTitle("Amble", "with special guest Dylan Gossett"))
            .isEqualTo(GigTitle("Amble / Dylan Gossett"))
        expectThat(billedTitle("Blossoms", "plus very special guests The Royston Club and Heidi Curtis"))
            .isEqualTo(GigTitle("Blossoms / The Royston Club / Heidi Curtis"))
    }

    @Test
    fun `reads a bill written after the name of a tour`() {
        expectThat(billedTitle("Courteeners", "God Bless The Band Tour - plus special guests The Lathums and The Guestlist"))
            .isEqualTo(GigTitle("Courteeners / The Lathums / The Guestlist"))
        expectThat(billedTitle("Enter Shikari", "LOSE YOUR SELF Tour with Holding Absence and The Callous Daoboys"))
            .isEqualTo(GigTitle("Enter Shikari / Holding Absence / The Callous Daoboys"))
        expectThat(billedTitle("Weezer", "The Gathering 2027 Tour with very special guests Taking Back Sunday & Beach Bunny"))
            .isEqualTo(GigTitle("Weezer / Taking Back Sunday / Beach Bunny"))
    }

    @Test
    fun `keeps the venue's title where the subheader is a strapline`() {
        listOf(
            "Emotional World Tour",
            "Distance In Static Live UK/EU Tour 2027",
            "Performing Live at Alexandra Palace Theatre",
            "A Camera Obscura at Alexandra Palace",
            "Directed by Martin Murphy & Suzy Catliff",
            "and the Royal Liverpool Philharmonic Orchestra perform If… and other works",
        ).forEach { expectThat(billedTitle("Headliner", it)).isEqualTo(null) }
    }

    @Test
    fun `keeps the venue's title where the bill isn't booked yet`() {
        expectThat(billedTitle("Headliner", "with special guests TBA")).isEqualTo(null)
        expectThat(billedTitle("Headliner", "plus support")).isEqualTo(null)
        expectThat(billedTitle("Headliner", "plus special guests")).isEqualTo(null)
    }

    @Test
    fun `keeps the venue's title where the page has no subheader`() {
        val page = pageOf("""<div id="event_content"><h1>Palace</h1></div>""")

        expectThat(AlexandraPalaceGigsSource(noHttp).billedTitle(page, GigTitle("Palace"))).isEqualTo(null)
    }

    private fun billedTitle(listedAs: String, subheader: String): GigTitle? {
        val page = pageOf("""<div id="event_content"><h1>$listedAs</h1><h3 class="subheader">$subheader</h3></div>""")
        return AlexandraPalaceGigsSource(noHttp).billedTitle(page, GigTitle(listedAs))
    }
}
