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

class AlexandraPalaceGigsSourceTest {

    @Test
    fun `extracts gig events from Alexandra Palace's what's on page`() {
        val events = assertScrapesGigs(
            source = AlexandraPalaceGigsSource(cachedClient()),
            size = 41,
            first = Gig(
                GigId(alexandraPalace.id, "https://www.alexandrapalace.com/whats-on/upside-down-london/"),
                // trailing   (narrow no-break space), not a plain space - it's what the
                // page's own title text actually contains, confirmed character-by-character
                // against a failed run before this literal was written
                GigTitle("Upside Down London "),
                GigDate(2026, 8, 1),
                PosterUrl("https://www.alexandrapalace.com/wp-content/uploads/2026/05/pl-udl-approved-media-assets-14-of-17-marked-2048x1536.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(alexandraPalace.id, "https://www.alexandrapalace.com/whats-on/kaleidoscope-festival-2/"),
                GigTitle("Kaleidoscope Festival"),
                GigDate(2027, 7, 10),
                PosterUrl("https://www.alexandrapalace.com/wp-content/uploads/2026/07/Kaleidescope-11.07.26-www.harbinson.uk-7159-2048x1366.jpg"),
                GigDescription(""),
            ),
            urlPrefix = "https://www.alexandrapalace.com/whats-on/",
        )
    }

    @Test
    fun `resolves the start date of a range, including one that crosses a calendar year`() {
        fun eventPage(dates: String) = """
            <div class="event_card_wrapper">
                <div class="event_img proportional_container"><img src="https://example.com/poster.jpg"></div>
                <header><p class="dates uc"><strong>$dates</strong></p>
                <a href="https://example.com/gig" class="event_target"><h3>Gig</h3></a></header>
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
}
