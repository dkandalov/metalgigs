package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test

// Why titles are tidied as read: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
// Why the poster comes at full size: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
class TheUnderworldGigsSourceTest {

    @Test
    fun `extracts gig events from The Underworld search-events page`() {
        val events = assertScrapesGigs(
            source = TheUnderworldGigsSource(cachedClient()),
            size = 74,
            first = Gig(
                GigId(theUnderworld.id, GigUrl("https://www.theunderworldcamden.co.uk/event/the-partisans-8th-aug-the-underworld-london-tickets/")),
                GigTitle("THE PARTISANS"),
                GigDate(2026, 8, 8),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-04-15/644411f7-5f86-484c-b29b-b71dc309b89e.jpg?rect=734%2C0%2C2682%2C2682"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theUnderworld.id, GigUrl("https://www.theunderworldcamden.co.uk/event/alive-a-tribute-to-pearl-jam-20th-nov-the-underworld-london-tickets/")),
                GigTitle("ALIVE, A TRIBUTE TO PEARL JAM"),
                GigDate(2027, 12, 4),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-02-10/cf613856-3e58-41a8-b0f0-af044c77c97b.jpg?rect=228%2C0%2C2045%2C2045"),
                GigDescription(""),
            ),
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

    // the running order off the Kings of Thrash page, whose lineup types the headliner in the case
    // the bands use where the listing heading shouts it
    @Test
    fun `titles a gig with its running order, headliner first`() {
        val page = runningOrderOf("7:00 PM" to "Doors open", "&ndash;" to "Wicked", "&ndash;" to " XIII Doors", "&ndash;" to "Kings of Thrash", "11:00 PM" to "Curfew")

        expectThat(TheUnderworldGigsSource(noHttp).billedTitle(page, GigTitle("KINGS OF THRASH")))
            .isEqualTo(GigTitle("Kings of Thrash / XIII Doors / Wicked"))
    }

    // London Metalfest, where the running order holds the day's bands and not what the day is called
    @Test
    fun `keeps the venue's title when the running order tops out on something the gig isn't named for`() {
        val page = runningOrderOf("1:00 PM" to "Doors open", "&ndash;" to "Aethoria", "&ndash;" to "Burning Witches", "&ndash;" to "Candlemass", "10:00 PM" to "Curfew")

        expectThat(TheUnderworldGigsSource(noHttp).billedTitle(page, GigTitle("LONDON METALFEST"))).isEqualTo(null)
    }

    // a club night, and a gig whose only act is the headliner - between doors and curfew there is
    // nothing the listing hasn't already said
    @Test
    fun `keeps the venue's title when the running order adds no one to it`() {
        val clubNight = runningOrderOf("11:00 PM" to "Doors open", "3:00 AM" to "Curfew")
        val oneAct = runningOrderOf("6:30 PM" to "Doors open", "&ndash;" to "SARCOFAGO", "10:00 PM" to "Curfew")

        expectThat(TheUnderworldGigsSource(noHttp).billedTitle(clubNight, GigTitle("Blackout Club"))).isEqualTo(null)
        expectThat(TheUnderworldGigsSource(noHttp).billedTitle(oneAct, GigTitle("SARCOFAGO"))).isEqualTo(null)
    }

    // the SPY page's five acts; the venue's longest running order is Cosmic Void Festival's 39
    @Test
    fun `takes the headliner and three supports from a longer bill`() {
        val page = runningOrderOf("7:00 PM" to "Doors open", "&ndash;" to "bullet.", "&ndash;" to "Shooting Daggers", "&ndash;" to "Dry Socket", "&ndash;" to "SPACED", "&ndash;" to "SPY", "11:00 PM" to "Curfew")

        expectThat(TheUnderworldGigsSource(noHttp).billedTitle(page, GigTitle("SPY")))
            .isEqualTo(GigTitle("SPY / SPACED / Dry Socket / Shooting Daggers"))
    }

    private fun runningOrderOf(vararg rows: Pair<String, String>) = pageOf(
        rows.joinToString("", """<ul class="event-details-lineup">""", "</ul>") { (time, act) ->
            "<li><time>$time</time><span>&middot;</span><p>$act</p></li>"
        }
    )
}
