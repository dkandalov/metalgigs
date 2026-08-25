package metalgigs.scrape

import metalgigs.GigDescription
import metalgigs.GigUrl
import metalgigs.GigTitle
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

// Why a source fails loudly: docs/adr/0002-a-source-fails-rather-than-publishing-something-plausible.md
// Why titles are tidied as read: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
class GigsSourceTest {

    // Dice hands a promoter's name back verbatim, and Alexandra Palace's own heading carries a narrow
    // no-break space - neither is a parsing failure, and no better parse avoids them
    @Test
    fun `normalises the spacing a venue types into a title`() {
        expectThat(titleFrom("LUN8 ")).isEqualTo(GigTitle("LUN8"))
        expectThat(titleFrom("The Fire Doors + The InCureables")).isEqualTo(GigTitle("The Fire Doors + The InCureables"))
        expectThat(titleFrom("Upside Down London ")).isEqualTo(GigTitle("Upside Down London"))
        expectThat(titleFrom(" Friends Brewery Quiz | Haggerston")).isEqualTo(GigTitle("Friends Brewery Quiz | Haggerston"))
        expectThat(titleFrom("Moving Pictures  (A Tribute)")).isEqualTo(GigTitle("Moving Pictures (A Tribute)"))
    }

    // the point of normalising only the spacing a venue can type: a selector that has started
    // matching a card's markup still reaches GigTitle and fails, rather than being tidied into a
    // title that looks fine
    @Test
    fun `leaves markup's own whitespace for GigTitle to refuse`() {
        assertFailsWith<IllegalArgumentException> { titleFrom("Doom Night\n7pm, doors 6:30") }
        assertFailsWith<IllegalArgumentException> { titleFrom("Doom Night\tSOLD OUT") }
    }

    @Test
    fun `leaves a title that needs nothing done to it alone`() {
        expectThat(titleFrom("Parish + Mägick Ritüal")).isEqualTo(GigTitle("Parish + Mägick Ritüal"))
    }

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
            fetchDescription(servingChangedMarkup, GigUrl("https://example.com/gig"), source::eventPageContent)
        }
        assertFailsWith<IllegalStateException> {
            fetchDescription(noHttp, GigUrl("https://example.com/gig"), source::eventPageContent)
        }
        // "" is only ever a page that was read and had nothing to say about its gig
        expectThat(fetchDescription(servingChangedMarkup, GigUrl("https://example.com/gig")) { "" }).isEqualTo(GigDescription(""))
    }

    @Test
    fun `names the gig when a listing gives no poster`() {
        expectThat(posterUrlFrom(GigUrl("https://example.com/gig"), "https://example.com/poster.jpg"))
            .isEqualTo(PosterUrl("https://example.com/poster.jpg"))
        // an unmatched selector and an absent api field, which is what each source can hand it
        listOf("", null).forEach { missing ->
            expectThat(assertFailsWith<IllegalStateException> { posterUrlFrom(GigUrl("https://example.com/gig"), missing) }.message.orEmpty())
                .contains("https://example.com/gig")
        }
    }
}
