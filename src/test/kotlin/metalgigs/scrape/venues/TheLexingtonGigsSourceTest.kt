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
            source = TheLexingtonGigsSource(cachedClient(), JoinedActs.keptWhole, 2026),
            size = 74,
            first = Gig(
                GigId(theLexington.id, GigUrl("https://thelexington.co.uk/event.php?id=3825")),
                GigTitle("CANCELLED - Milo Korbenski"),
                GigDate(2026, 11, 25),
                PosterUrl("https://thelexington.co.uk/uploads/3825.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(theLexington.id, GigUrl("https://thelexington.co.uk/event.php?id=3964")),
                GigTitle("Razors, Menace, Denizens"),
                GigDate(2027, 6, 19),
                PosterUrl("https://thelexington.co.uk/uploads/3964.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )
    }

    // 15 of the 97 cards listed on 2026-11-25 are club nights, and the venue says which on the card
    @Test
    fun `leaves the venue's club nights off, taking only what it lists as a gig`() {
        val gigs = TheLexingtonGigsSource(fakeSite(clubCard("White Heat Club", "Sat 16th January")), JoinedActs.keptWhole, 2026).latestGigs()

        expectThat(gigs.map { it.title.value }).isEqualTo(listOf("Wild Pink, newhvn"))
    }

    // 8 more of those 97 cards carry no label at all: the Monday pop quiz seven times and a free DJ night
    @Test
    fun `leaves off a night the venue labels as nothing`() {
        val quiz = unlabelledCard("Lexington Pop Quiz with Paul Guided Missile", "Mon 14th September")
        val gigs = TheLexingtonGigsSource(fakeSite(quiz), JoinedActs.keptWhole, 2026).latestGigs()

        expectThat(gigs.map { it.title }).isEqualTo(listOf(GigTitle("Wild Pink, newhvn")))
    }

    @Test
    fun `counts the year forward as the listing crosses into an earlier month`() {
        val january = gigCard(3801, "Sorry", "Sat 16th January")
        val dates = TheLexingtonGigsSource(fakeSite(january), JoinedActs.keptWhole, 2026).latestGigs().map { it.date }

        expectThat(dates).isEqualTo(listOf(GigDate(2026, 9, 7), GigDate(2027, 1, 16)))
    }

    // 16 January is a Saturday in 2027 and a Friday in 2026
    @Test
    fun `fails rather than dating a gig on a day its own card contradicts`() {
        val misdated = fakeSite(gigCard(3801, "Sorry", "Fri 16th January"))
        val failure = runCatching { TheLexingtonGigsSource(misdated, JoinedActs.keptWhole, 2026).latestGigs() }.exceptionOrNull()

        expectThat(failure?.message.orEmpty().contains("no longer lands on the day the card prints")).isTrue()
    }

    @Test
    fun `scopes the event page to the promoter's copy and the running order`() {
        val html = """
            <section class="event-panel">
                <div class="event-poster"><img src="../uploads/3786.jpg" alt="Wild Pink, newhvn"></div>
                <div class="event-details">
                    <div class="event-tags"><span class="tag gig">Gig</span></div>
                    <h1 class="serif-heading">Wild Pink, newhvn</h1>
                    <div class="info-rows">
                        <div class="info-row"><span class="k">Date</span><span class="v">Monday 7th September 2026</span></div>
                        <div class="info-row"><span class="k">Price</span><span class="v">adv £20.30</span></div>
                    </div>
                    <div class="stage-times">
                        <p class="hours-label">Stage Times</p>
                        <div class="stage-row">8PM NEWHVN</div>
                        <div class="stage-row">9PM WILD PINK</div>
                    </div>
                    <div class="event-desc">Still Coming Down is Wild Pink's sixth studio album.<BR><BR>Out now.</div>
                    <div class="btn-row"><a href="https://link.dice.fm/O8d" class="pill pill-filled">Get Tickets</a></div>
                    <p class="id-note">The Lexington is an 18+ venue &ndash; make sure to bring ID.</p>
                </div>
            </section>
            <section class="coming-up">
                ${clubCard("White Heat Club", "Fri 11th September")}
            </section>
        """.trimIndent()

        val copy = TheLexingtonGigsSource(noHttp, JoinedActs.keptWhole, 2026).eventPageContent(pageOf(html))!!

        expectThat(copy).isEqualTo("8PM NEWHVN 9PM WILD PINK Still Coming Down is Wild Pink's sixth studio album. Out now.")
        // the date is already a field, and the price, ticket link and age policy say nothing about the act
        expectThat(copy.contains("adv £20.30")).isFalse()
        expectThat(copy.contains("Get Tickets")).isFalse()
        expectThat(copy.contains("18+ venue")).isFalse()
        // every page closes with a rail of the venue's next five nights, other gigs' titles among them
        expectThat(copy.contains("White Heat Club")).isFalse()
    }

    @Test
    fun `reads an event page with no copy block as one that said nothing`() {
        val html = """<div class="event-details"><h1 class="serif-heading">Wild Pink, newhvn</h1></div>"""

        expectThat(TheLexingtonGigsSource(noHttp, JoinedActs.keptWhole, 2026).eventPageContent(pageOf(html))).isEqualTo("")
    }

    // Why this copy is no copy: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    @Test
    fun `reads copy that says only that the act plays live as no copy at all`() {
        fun copyOf(paragraph: String) =
            TheLexingtonGigsSource(noHttp, JoinedActs.keptWhole, 2026).eventPageContent(pageOf("""<div class="event-details"><div class="event-desc">$paragraph</div></div>"""))

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
            <div class="events-grid">
                ${gigCard(3786, "Wild Pink, newhvn", "Mon 7th September")}
                $second
            </div>
            <div class="event-details"><div class="event-desc">An evening of something.</div></div>
        """.trimIndent()
        return { Response(OK).body(body) }
    }

    private fun gigCard(id: Int, title: String, date: String) = card("""<span class="event-label">Gig</span>""", id, title, date)

    private fun clubCard(title: String, date: String) = card("""<span class="event-label club">Club</span>""", 3875, title, date)

    private fun unlabelledCard(title: String, date: String) = card("", 3971, title, date)

    private fun card(label: String, id: Int, title: String, date: String) = """
        <article class="event-card">
            <a href="./event.php?id=$id" class="event-img" aria-label="$title – read more">
                $label
                <img src="../uploads/$id.jpg" alt="$title" loading="lazy">
            </a>
            <div class="event-body">
                <p class="event-date">$date · 7:30pm</p>
                <h2 class="event-title"><a href="./event.php?id=$id">$title</a></h2>
                <div class="event-actions">
                    <a href="./event.php?id=$id" class="pill pill-outline">Read More</a>
                    <a href="https://link.dice.fm/O8d" class="pill pill-filled">Get Tickets</a>
                </div>
            </div>
        </article>
    """.trimIndent()

    @Test
    fun `takes a gig's lineup from a heading that bills its acts`() {
        val gigs = TheLexingtonGigsSource(cachedClient(), JoinedActs.keptWhole, 2026).latestGigs()

        expectThat(gigs.first { it.title == GigTitle("Razors, Menace, Denizens") }.lineup).isEqualTo(Lineup(listOf("Razors", "Menace", "Denizens")))
    }
}
