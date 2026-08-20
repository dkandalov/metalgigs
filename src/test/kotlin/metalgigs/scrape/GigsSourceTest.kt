package metalgigs.scrape

import metalgigs.GigDescription
import metalgigs.PosterUrl
import metalgigs.scrape.venues.TheUnderworldGigsSource
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GigsSourceTest {

    // Extraction that matches nothing is what a changed site looks like, and it reaches the gig as a
    // blank description rather than as a failed scrape.
    @Test
    fun `fails rather than building a gig whose description its page never gave`() {
        val changedMarkup = "<div>page markup changed, no article.event here</div>"
        val servingChangedMarkup: HttpHandler = { Response(OK).body(changedMarkup) }
        val source = TheUnderworldGigsSource(noHttp)

        expectThat(source.eventPageContent(pageOf(changedMarkup))).isEqualTo(null)
        // markup that no longer matches, and a page that won't fetch at all, both fail outright
        assertFailsWith<IllegalStateException> {
            fetchDescription(servingChangedMarkup, "https://example.com/gig", source::eventPageContent)
        }
        assertFailsWith<IllegalStateException> {
            fetchDescription(noHttp, "https://example.com/gig", source::eventPageContent)
        }
        // "" is only ever a page that was read and had nothing to say about its gig
        expectThat(fetchDescription(servingChangedMarkup, "https://example.com/gig") { "" }).isEqualTo(GigDescription(""))
    }

    @Test
    fun `names the gig when a listing gives no poster`() {
        expectThat(posterUrlFrom("https://example.com/gig", "https://example.com/poster.jpg"))
            .isEqualTo(PosterUrl("https://example.com/poster.jpg"))
        // an unmatched selector and an absent api field, which is what each source can hand it
        listOf("", null).forEach { missing ->
            expectThat(assertFailsWith<IllegalStateException> { posterUrlFrom("https://example.com/gig", missing) }.message.orEmpty())
                .contains("https://example.com/gig")
        }
    }
}
