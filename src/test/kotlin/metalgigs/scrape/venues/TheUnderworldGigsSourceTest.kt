package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test

class TheUnderworldGigsSourceTest {

    @Test
    fun `extracts gig events from The Underworld search-events page`() {
        val events = assertScrapesGigs(
            source = TheUnderworldGigsSource(cachedClient()),
            size = 74,
            first = Gig(
                GigId(theUnderworld.id, "https://www.theunderworldcamden.co.uk/event/the-partisans-8th-aug-the-underworld-london-tickets/"),
                GigTitle("THE PARTISANS"),
                GigDate(2026, 8, 8),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-04-15/644411f7-5f86-484c-b29b-b71dc309b89e.jpg?rect=734%2C0%2C2682%2C2682"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theUnderworld.id, "https://www.theunderworldcamden.co.uk/event/alive-a-tribute-to-pearl-jam-20th-nov-the-underworld-london-tickets/"),
                GigTitle("ALIVE, A TRIBUTE TO PEARL JAM"),
                GigDate(2027, 12, 4),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-02-10/cf613856-3e58-41a8-b0f0-af044c77c97b.jpg?rect=228%2C0%2C2045%2C2045"),
                GigDescription(""),
            ),
            urlPrefix = "https://www.theunderworldcamden.co.uk/event/",
        )

        // the listing asks imgix for w=200 thumbnails; keeping that would publish 200px images for
        // this venue and nothing downstream could recover the detail, so no image url keeps a width
        expectThat(events.count { it.posterUrl.value.contains("w=") }).isEqualTo(0)
        expectThat(events.count { it.posterUrl.value.contains("imgix.net") }).isEqualTo(73)
    }

    @Test
    fun `strips only the width, leaving other imgix parameters and non-imgix urls alone`() {
        val rect = "https://dice-media.imgix.net/a.jpg?rect=1%2C0%2C99%2C99"

        expectThat(imgixUrlWithoutWidth("$rect&w=200")).isEqualTo(rect)
        expectThat(imgixUrlWithoutWidth("https://dice-media.imgix.net/a.jpg?w=200&rect=1")).isEqualTo("https://dice-media.imgix.net/a.jpg?rect=1")
        expectThat(imgixUrlWithoutWidth("https://dice-media.imgix.net/a.jpg?w=200")).isEqualTo("https://dice-media.imgix.net/a.jpg")
        expectThat(imgixUrlWithoutWidth(rect)).isEqualTo(rect)
        // a width elsewhere isn't imgix's, so it's left alone rather than guessed at
        expectThat(imgixUrlWithoutWidth("https://example.com/a.jpg?w=200")).isEqualTo("https://example.com/a.jpg?w=200")
    }

    // the age policy and the share links are verbatim from a real event page, where between them
    // they outran the gig's own blurb
    @Test
    fun `scopes The Underworld page text to the gig's own content, ignoring other-events widgets`() {
        val html = """
            <article class="event">
              <div class="content">
                <p>Doom metal night!</p>
                <p>This is a 14+ event. 14 and 15 year olds MUST be accompanied by an adult (18+) / All ticketholders under the age of 25 will be required to carry PHOTO ID</p>
              </div>
              <footer class="section"><ul class="event-share"><li><a>Share</a></li><li><a>Tweet</a></li></ul></footer>
            </article>
            <article class="list">
              <h3 class="list-header-title">KINGS OF THRASH</h3>
            </article>
        """.trimIndent()

        val pageText = TheUnderworldGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("KINGS OF THRASH")).isEqualTo(false)
        expectThat(pageText.contains("14+")).isEqualTo(false)
        expectThat(pageText.contains("PHOTO ID")).isEqualTo(false)
        expectThat(pageText.contains("Share")).isEqualTo(false)
        expectThat(pageText.contains("Tweet")).isEqualTo(false)
    }

    // the same policy paragraph, worded the other way round, on a gig with an 18+ door
    @Test
    fun `drops The Underworld age policy however it is worded`() {
        val html = """
            <article class="event">
              <div class="content">
                <p>Doom metal night!</p>
                <p>This event is an 18+ event</p>
              </div>
            </article>
        """.trimIndent()

        val pageText = TheUnderworldGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText).isEqualTo("Doom metal night!")
    }
}
