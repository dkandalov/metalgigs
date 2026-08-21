package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test

class ElectricBrixtonGigsSourceTest {

    @Test
    fun `extracts gig events from Electric Brixton's events page, following pagination`() {
        assertScrapesGigs(
            source = ElectricBrixtonGigsSource(cachedClient()),
            size = 54,
            first = Gig(
                GigId(electricBrixton.id, "https://www.electricbrixton.uk.com/events/bacchanal-friday-4/"),
                GigTitle("Bacchanal Friday"),
                GigDate(2026, 8, 28),
                PosterUrl("https://e2h4j4t3.rocketcdn.me/wp-content/uploads/2025/01/Busspepper-1200.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(electricBrixton.id, "https://www.electricbrixton.uk.com/events/elder/"),
                GigTitle("Elder"),
                GigDate(2027, 2, 27),
                PosterUrl("https://e2h4j4t3.rocketcdn.me/wp-content/uploads/2026/06/Elder-1200.jpg"),
                GigDescription(""),
            ),
        )
    }

    // the door/price/age furniture is verbatim from a real listing, and repeats on every one of them
    @Test
    fun `scopes Electric Brixton page text to the gig's own copy`() {
        val html = """
            <nav><a>What's On</a></nav>
            <div class="split-top event-info">
                <h4>19:00 - 23:00</h4><h4>£50 + BF</h4>
            </div>
            <div class="split-bottom event-desc">
                <p>Doom Promotions Presents</p><p>Doom Night</p><p>18+ (Physical photo ID required)</p>
            </div>
            <div class="event-item event-context">
                <p>Doom metal night, with Kings Of Thrash in support.</p>
            </div>
            <div class="uabb-subscribe-form"><label>Sign up to our mailing list</label></div>
        """.trimIndent()

        val pageText = ElectricBrixtonGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night, with Kings Of Thrash in support.")).isTrue()
        expectThat(pageText.contains("Physical photo ID required")).isEqualTo(false)
        expectThat(pageText.contains("£50 + BF")).isEqualTo(false)
        expectThat(pageText.contains("mailing list")).isEqualTo(false)
        expectThat(pageText.contains("What's On")).isEqualTo(false)
    }
}
