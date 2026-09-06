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
class DingwallsGigsSourceTest {

    @Test
    fun `extracts gig events from Dingwalls whats-on page`() {
        assertScrapesGigs(
            source = DingwallsGigsSource(cachedClient()),
            size = 38,
            first = Gig(
                GigId(dingwalls.id, GigUrl("https://dingwalls.com/gig/isabel-van-gelde/")),
                GigTitle("Isabel Van Gelder"),
                GigDate(2026, 9, 8),
                PosterUrl("https://dingwalls.com/wp-content/uploads/elementor/thumbs/PP-3-rjwsim2adcy5bibwgefvk12na485ugaltii7914mr0.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(dingwalls.id, GigUrl("https://dingwalls.com/gig/future-of-the-left-curses-20th-anniversary/")),
                GigTitle("Future of the Left (Curses 20th Anniversary)"),
                GigDate(2027, 2, 12),
                PosterUrl("https://dingwalls.com/wp-content/uploads/elementor/thumbs/PP-25-rqj6o5v970jjsk91d02jbo206nscvgcoveod9yotgs.jpg"),
                GigDescription(""),
            ),
        )
    }

    // The grid renders 24 of 38 and loads the rest on scroll, so page one reads as a whole listing.
    // Why a listing is paged by the link the page follows: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
    @Test
    fun `follows the load-more anchor past the cards the grid renders`() {
        val gigs = DingwallsGigsSource(pagedSite(maxPage = 3)).latestGigs()

        expectThat(gigs.map { it.title.value }).isEqualTo(listOf("Gig 1", "Gig 2", "Gig 3"))
    }

    // The last page advertises a next one all the same - page 2 of 2 points at /3/, which renders
    // no cards - so following data-next-page while it is there would fetch a page past the end and,
    // on a listing whose last page happened to be full, read as a listing that simply stopped.
    @Test
    fun `stops on the page count the anchor declares, not on the next link it keeps offering`() {
        val gigs = DingwallsGigsSource(pagedSite(maxPage = 2)).latestGigs()

        expectThat(gigs.map { it.title.value }).isEqualTo(listOf("Gig 1", "Gig 2"))
    }

    @Test
    fun `fails rather than stopping quietly on a listing that never reaches its last page`() {
        val failure = runCatching { DingwallsGigsSource(pagedSite(maxPage = 99)).latestGigs() }.exceptionOrNull()

        expectThat(failure?.message.orEmpty().contains("still offers a next page")).isTrue()
    }

    // one card per page, so the page number is also the gig it carries; every page keeps offering a
    // next one the way the real last page does
    private fun pagedSite(maxPage: Int): HttpHandler = { request ->
        val page = request.uri.path.trimEnd('/').substringAfterLast('/').toIntOrNull() ?: 1
        Response(OK).body(
            """
            <div class="gig">
                <div class="elementor-widget-heading">Wednesday ${page}nd September 2026</div>
                <div class="elementor-widget-theme-post-title"><a href="https://dingwalls.com/gig/gig-$page/">Gig $page</a></div>
                <div class="elementor-widget-theme-post-featured-image"><img src="https://dingwalls.com/gig-$page.jpg"></div>
            </div>
            <div class="e-load-more-anchor" data-page="$page" data-max-page="$maxPage"
                 data-next-page="https://dingwalls.com/whats-on/${page + 1}/"></div>
            <div class="elementor-location-single"><p>An evening of something.</p></div>
            """.trimIndent()
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
