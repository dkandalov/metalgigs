package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.time.LocalDate
import kotlin.test.Test

class DingwallsGigsSourceTest {

    @Test
    fun `extracts gig events from Dingwalls whats-on page`() {
        assertScrapesGigs(
            source = DingwallsGigsSource(cachedClient()),
            size = 24,
            first = Gig(
                GigId(dingwalls.id, "https://dingwalls.com/gig/root-company/"),
                GigTitle("BANG YONGGUK"),
                LocalDate.of(2026, 9, 2),
                PosterUrl("https://dingwalls.com/wp-content/uploads/elementor/thumbs/PP-5-ropdtf0hg2d9yqdycam42ynoc5vdz4n4gsylj8c3l8.png"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(dingwalls.id, "https://dingwalls.com/gig/rock-for-hope-2/"),
                GigTitle("Rock For Hope"),
                LocalDate.of(2026, 11, 7),
                PosterUrl("https://dingwalls.com/wp-content/uploads/elementor/thumbs/PP-27-rr5voszodg8dz4qw6s0thhnj6cm8eai4qgy0bw9ru4.jpg"),
                GigDescription(""),
            ),
            urlPrefix = "https://dingwalls.com/gig/",
        )
    }

    @Test
    fun `scopes Dingwalls page text to the Elementor single-page template`() {
        val html = """
            <nav><a>Home</a></nav>
            <div data-elementor-type="single-page" class="elementor elementor-750 elementor-location-single">
                <h1>Doom Night</h1>
                <div class="elementor-widget-theme-post-content"><p>Doom metal night!</p></div>
            </div>
            <footer><a>Instagram</a></footer>
        """.trimIndent()

        val pageText = DingwallsGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Home")).isEqualTo(false)
    }
}
