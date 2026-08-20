package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.time.LocalDate
import kotlin.test.Test

class PaperDressVintageGigsSourceTest {

    @Test
    fun `extracts gig events from Paper Dress Vintage's by-night page`() {
        assertScrapesGigs(
            source = PaperDressVintageGigsSource(cachedClient()),
            size = 46,
            first = Gig(
                GigId(paperDressVintage.id, "https://paperdressvintage.co.uk/?p=18710"),
                GigTitle("That 70s Night ft. Vintage Voltage"),
                LocalDate.of(2026, 8, 14),
                PosterUrl("http://paperdressvintage.co.uk/wp-content/uploads/2026/07/poster-aug-14th-pd1-scaled.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(paperDressVintage.id, "https://paperdressvintage.co.uk/?p=18815"),
                GigTitle("Sam Scherdel"),
                LocalDate.of(2026, 12, 10),
                PosterUrl("http://paperdressvintage.co.uk/wp-content/uploads/2026/07/Sam-Scherdel.jpg"),
                GigDescription(""),
            ),
            urlPrefix = "https://paperdressvintage.co.uk/",
        )
    }

    @Test
    fun `scopes Paper Dress Vintage page text to the event content, excluding nav and footer`() {
        val html = """
            <nav><a>Home</a><a>Book a table</a></nav>
            <div class="event__content"><p>Doom metal night!</p></div>
            <footer><a>Contact</a><a>Opening hours</a></footer>
        """.trimIndent()

        val pageText = PaperDressVintageGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Opening hours")).isEqualTo(false)
    }
}
