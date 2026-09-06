package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isTrue
import kotlin.test.Test

// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
// Why the year is counted forward: docs/adr/0010-a-date-is-read-per-venue-and-a-missing-year-is-inferred.md
class TheLexingtonGigsSourceTest {

    @Test
    fun `extracts gig events from The Lexington's what's on page`() {
        assertScrapesGigs(
            source = TheLexingtonGigsSource(cachedClient(), 2026),
            size = 83,
            first = Gig(
                GigId(theLexington.id, GigUrl("https://thelexington.co.uk/event.php?id=3720")),
                GigTitle("Massive Ego"),
                GigDate(2026, 9, 6),
                PosterUrl("https://thelexington.co.uk/uploads/3720.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theLexington.id, GigUrl("https://thelexington.co.uk/event.php?id=3883")),
                GigTitle("Bis, Liines"),
                GigDate(2027, 2, 19),
                PosterUrl("https://thelexington.co.uk/uploads/3883.jpg"),
                GigDescription(""),
            ),
        )
    }

    // 17 of the 100 cards listed on 2026-09-06 are club nights, and the venue says which on the card
    @Test
    fun `leaves the venue's club nights off, taking only what it lists as a gig`() {
        val gigs = TheLexingtonGigsSource(fakeSite(clubCard("White Heat Club", "Sat January 16")), 2026).latestGigs()

        expectThat(gigs.map { it.title.value }).isEqualTo(listOf("Wild Pink, newhvn"))
    }

    @Test
    fun `counts the year forward as the listing crosses into an earlier month`() {
        val january = gigCard(3801, "Sorry", "Sat January 16")
        val dates = TheLexingtonGigsSource(fakeSite(january), 2026).latestGigs().map { it.date }

        expectThat(dates).isEqualTo(listOf(GigDate(2026, 9, 7), GigDate(2027, 1, 16)))
    }

    // 16 January is a Saturday in 2027 and a Friday in 2026
    @Test
    fun `fails rather than dating a gig on a day its own card contradicts`() {
        val misdated = fakeSite(gigCard(3801, "Sorry", "Fri January 16"))
        val failure = runCatching { TheLexingtonGigsSource(misdated, 2026).latestGigs() }.exceptionOrNull()

        expectThat(failure?.message.orEmpty().contains("no longer lands on the day the card prints")).isTrue()
    }

    @Test
    fun `scopes the event page to the promoter's copy and the running order`() {
        val html = """
            <div class="column"><img src="../uploads/3786.jpg" alt="Wild Pink, newhvn"></div>
            <div class="column text-content">
                <h1>Wild Pink, newhvn</h1>
                <div class="event-meta"><strong>Monday, September 07, 19:30</strong><br>adv £20.30</div>
                <p>8PM NEWHVN<br /><br />9PM WILD PINK </p>
                <p>Still Coming Down is Wild Pink's sixth studio album.</p>
                <div class="links"><a href="https://link.dice.fm/O8d" class="box-link">Get Tickets</a></div>
                <BR><BR>
                The Lexington is an 18+ venue - make sure to bring ID.
            </div>
        """.trimIndent()

        val copy = TheLexingtonGigsSource(noHttp, 2026).eventPageContent(pageOf(html))!!

        expectThat(copy.contains("9PM WILD PINK")).isTrue()
        expectThat(copy.contains("sixth studio album")).isTrue()
        // the date is already a field, and the price and ticket link say nothing about the act
        expectThat(copy.contains("adv £20.30")).isFalse()
        expectThat(copy.contains("Get Tickets")).isFalse()
        // the venue's age policy is a bare text node in the same div, belonging to no element of its
        // own - so it is left out by taking the paragraphs rather than by being cut
        expectThat(copy.contains("18+ venue")).isFalse()
    }

    // Why this copy is no copy: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    @Test
    fun `reads copy that says only that the act plays live as no copy at all`() {
        fun copyOf(paragraph: String) =
            TheLexingtonGigsSource(noHttp, 2026).eventPageContent(pageOf("""<div class="text-content"><p>$paragraph</p></div>"""))

        expectThat(copyOf("live")).isEqualTo("")
        expectThat(copyOf("play live")).isEqualTo("")
        expectThat(copyOf("plays live,")).isEqualTo("")
        expectThat(copyOf("Mary Middlefield plays live")).isEqualTo("")
        // the act saying it is good live is the venue writing something, where the above is not
        expectThat(copyOf("They are incredible live")).isEqualTo("They are incredible live")
        expectThat(copyOf("it's an indie-pop xmas with Skep Arts!")).isEqualTo("it's an indie-pop xmas with Skep Arts!")
    }

    // the same body answers both the listing request and the event-page request that follows it
    private fun fakeSite(second: String): HttpHandler {
        val body = """
            <div class="grid-container">
                <div class="grid-item empty"></div>
                ${gigCard(3786, "Wild Pink, newhvn", "Mon September 07")}
                $second
            </div>
            <div class="column text-content"><p>An evening of something.</p></div>
        """.trimIndent()
        return { Response(OK).body(body) }
    }

    private fun gigCard(id: Int, title: String, date: String) = card("grid-item", "gig-label", "GIG", id, title, date)

    private fun clubCard(title: String, date: String) = card("grid-item-club", "club-label", "CLUB", 3875, title, date)

    private fun card(gridClass: String, labelClass: String, label: String, id: Int, title: String, date: String) = """
        <div class="$gridClass">
            <div class="image-container">
                <div class="image-wrapper"><img src="../uploads/$id.jpg" alt="$title"></div>
                <span class="$labelClass">$label</span>
                <a href="https://link.dice.fm/O8d" class="ticket-box">Get Tickets</a>
            </div>
            <div class="event-title">$title</div>
            <div class="event-date">$date, 19:30</div>
            <a href="./event.php?id=$id" class="link-box">Read More</a>
        </div>
    """.trimIndent()
}
