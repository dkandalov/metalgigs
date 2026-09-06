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
class RoundhouseGigsSourceTest {

    @Test
    fun `extracts gig events from the Roundhouse whats-on page`() {
        assertScrapesGigs(
            source = RoundhouseGigsSource(cachedClient()),
            size = 53,
            first = Gig(
                GigId(roundhouse.id, GigUrl("https://www.roundhouse.org.uk/whats-on/bellaire/")),
                GigTitle("One Special Night at Roundhouse: Bellaire"),
                GigDate(2026, 9, 12),
                PosterUrl("https://assets.roundhouse.org.uk/app/uploads/2026/02/Bellaire.png"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(roundhouse.id, GigUrl("https://www.roundhouse.org.uk/whats-on/angele/")),
                GigTitle("Angèle"),
                GigDate(2027, 5, 1),
                PosterUrl("https://assets.roundhouse.org.uk/app/uploads/2026/08/Featured-Image-1-2.png"),
                GigDescription(""),
            ),
        )
    }

    // The listing renders nine cards and loads the rest on scroll, so page one reads as the whole
    // listing - which is what this source took it for until 2026-09-06, publishing 9 of the venue's
    // 53 events for its first month.
    // Why a listing is paged by the link the page follows: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
    @Test
    fun `follows the listing's own next link past the nine cards page one renders`() {
        val gigs = RoundhouseGigsSource(pagedSite(lastPage = 3)).latestGigs()

        expectThat(gigs.map { it.title.value }).isEqualTo(listOf("Gig 1", "Gig 2", "Gig 3"))
    }

    // The bound fails rather than stopping, a listing that quietly ends being the bug being fixed
    // here rather than an acceptable answer to it.
    @Test
    fun `fails rather than stopping quietly on a listing that never runs out of next links`() {
        val failure = runCatching { RoundhouseGigsSource(pagedSite(lastPage = 99)).latestGigs() }.exceptionOrNull()

        expectThat(failure?.message.orEmpty().contains("still offers a next page")).isTrue()
    }

    // one card per page, so the page number is also the gig it carries
    private fun pagedSite(lastPage: Int): HttpHandler = { request ->
        val page = request.uri.path.substringAfter("/page/", "1").substringBefore('/').toInt()
        val next =
            if (page < lastPage) """<a class="next page-numbers" href="/whats-on/page/${page + 1}/?type=event">Next</a>"""
            else ""
        Response(OK).body(
            """
            <div class="event-card">
                <a href="https://www.roundhouse.org.uk/whats-on/gig-$page/" class="event-card__link"></a>
                <div class="event-card__image"><img src="https://assets.roundhouse.org.uk/gig-$page.jpg"></div>
                <h3 class="event-card__title">Gig $page</h3>
                <p class="event-card__date">Wed 12 Aug 26</p>
            </div>
            <div class="infinite-list__pagination">$next</div>
            <section class="event-about"><p>An evening of something.</p></section>
            """.trimIndent()
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
