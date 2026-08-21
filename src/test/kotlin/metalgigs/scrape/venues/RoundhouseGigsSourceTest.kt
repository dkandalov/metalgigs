package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test

class RoundhouseGigsSourceTest {

    @Test
    fun `extracts gig events from the Roundhouse whats-on page`() {
        assertScrapesGigs(
            source = RoundhouseGigsSource(cachedClient()),
            size = 9,
            first = Gig(
                GigId(roundhouse.id, "https://www.roundhouse.org.uk/whats-on/cf-kristen-schaal-the-legend/"),
                GigTitle("Kristen Schaal: The Legend of Crystal Shell"),
                GigDate(2026, 8, 17),
                PosterUrl("https://assets.roundhouse.org.uk/app/uploads/2026/04/Kristen-Schaal-4.png"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(roundhouse.id, "https://www.roundhouse.org.uk/whats-on/roger-taylor/"),
                GigTitle("Roger Taylor"),
                GigDate(2026, 9, 28),
                PosterUrl("https://assets.roundhouse.org.uk/app/uploads/2026/06/Roger_Taylor_London_1260x1280.jpg"),
                GigDescription(""),
            ),
        )
    }

    // ".event-about" holds the real description alongside a "Related events" carousel and a booking
    // card *nested inside it*, not as sibling sections - that's why the exclusions are by class, not
    // by boundary. The card's 142 words of booking schedule, digital-ticket notice and
    // restoration-levy small print are identical on every venue-run page
    @Test
    fun `scopes Roundhouse page text to the event content, excluding the nested related-events and booking blocks`() {
        val html = """
            <div class="event-hero__heading-wrapper"><h1>Doom Night</h1></div>
            <section class="event-about">
                <div class="layout-block layout-block--text-block-with-title"><p>Doom metal night!</p></div>
                <div class="layout-block layout-block--event-listing-card"><p>This event is digitally ticketed.</p></div>
                <div class="layout-block layout-block--related-events-list"><h3>Related events</h3><p>Other Gig</p></div>
            </section>
        """.trimIndent()

        val pageText = RoundhouseGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Other Gig")).isEqualTo(false)
        expectThat(pageText.contains("digitally ticketed")).isEqualTo(false)
    }

    // a promoter-run show puts its copy straight into ".event-about" with none of the layout blocks
    // the venue's own listings use, and has no hero heading wrapper at all
    @Test
    fun `takes Roundhouse page text from a promoter-run page that has no layout blocks`() {
        val html = """
            <section class="event-about">
                <div class="layout layout--main"><p>Doom metal night!</p></div>
            </section>
        """.trimIndent()

        expectThat(RoundhouseGigsSource(noHttp).eventPageContent(pageOf(html))).isEqualTo("Doom metal night!")
    }
}
