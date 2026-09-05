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
// Why the poster comes off the event page: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
class BushHallGigsSourceTest {

    @Test
    fun `extracts gig events from Bush Hall's See Tickets listing`() {
        assertScrapesGigs(
            source = BushHallGigsSource(cachedClient()),
            size = 56,
            first = Gig(
                GigId(bushHall.id, GigUrl("https://bushhall.seetickets.com/event/punks-for-charity-presents-class-of-79/bush-hall/3625627")),
                GigTitle("PUNKS FOR CHARITY PRESENTS CLASS OF '79"),
                GigDate(2026, 9, 5),
                PosterUrl("https://c.ststat.net/content/entimg/show/9d950176-681f-4816-b0a6-8a945b8a134a.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(bushHall.id, GigUrl("https://bushhall.seetickets.com/event/bec-o-malley/bush-hall/3707284")),
                GigTitle("Bec O'Malley"),
                GigDate(2027, 5, 7),
                PosterUrl("https://c.ststat.net/content/entimg/tour/bec-o-malley-1724965125-300x300.jpg"),
                GigDescription(""),
            ),
        )
    }

    // Only the two event pages the expectations above read are committed, the other 54 falling
    // through to the one page recorded per host (TrafficFixtures) - so those two posters are each
    // gig's own. The card and page here disagree the way the real ones do, 154x154 against 300x300.
    @Test
    fun `takes the poster from the event page rather than the card's thumbnail`() {
        val gig = onlyGigFrom(
            card = """
                <img data-src="https://c.ststat.net/content/entimg/tour/keywest-1910487254-154x154.jpg" alt="Keywest" />
            """.trimIndent(),
            eventPageHead = """
                <meta property="og:image" content="https://c.ststat.net/content/entimg/tour/keywest-1910487254-300x300.jpg">
            """.trimIndent(),
        )

        expectThat(gig.posterUrl)
            .isEqualTo(PosterUrl("https://c.ststat.net/content/entimg/tour/keywest-1910487254-300x300.jpg"))
    }

    @Test
    fun `marks a sold-out or cancelled gig in its title, and leaves an on-sale one alone`() {
        fun titleWithStatus(status: String) = onlyGigFrom(statusMarkup = status).title

        expectThat(titleWithStatus("")).isEqualTo(GigTitle("Liam Bailey"))
        expectThat(titleWithStatus("""<span class="v2-price-status">Sold out</span>"""))
            .isEqualTo(GigTitle("Liam Bailey - SOLD OUT"))
        expectThat(titleWithStatus("""<span class="v2-price-status">Cancelled</span>"""))
            .isEqualTo(GigTitle("Liam Bailey - CANCELLED"))
    }

    // the numeric date printed beside it reads as a different month for any day under 13
    @Test
    fun `reads the date the listing writes in full, not the numeric one beside it`() {
        expectThat(onlyGigFrom().date).isEqualTo(GigDate(2026, 9, 23))
    }

    // Why a page with no copy is blank rather than a failure: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    @Test
    fun `scopes the event page to the promoter's copy, and reads a page without any as blank`() {
        val boilerplate = """
            <div class="g-grid-col x3 xtf xif"><section class="g-ui-box desktop-only cca-desk-container">
                <p>Booking fees apply</p>
            </section></div>
        """.trimIndent()
        val narratives = """
            <section class="g-event-narratives">
                <header class="g-ui-box-header"><h2 class="g-ui-box-title border">More information about Jamie Lenman tickets</h2></header>
                <div class="g-ui-box-content"><div class="notranslate"><p>Jamie Lenman</p><p>Plus special guests</p></div></div>
            </section>
        """.trimIndent()
        fun detailColumn(copy: String) = """
            $boilerplate
            <div class="g-grid-col x9"><section class="g-ui-box">
                <div id="tabs" class="pv-shared-event-price-list-form"><h4>LOW INCOME TICKET</h4>
                    <div class="price-narr-cont">FAQs about the Low Income £5 tickets:</div>
                </div>
                <div class="g-driver tight small link icon g-venue-accessibility-link"><a>Venue accessibility</a></div>
                $copy
            </section></div>
        """.trimIndent()

        val source = BushHallGigsSource(noHttp)
        val copy = source.eventPageContent(pageOf(detailColumn(narratives)))!!

        expectThat(copy.contains("Plus special guests")).isTrue()
        // See Tickets' own furniture, all of it inside or beside the column the copy is scoped to
        expectThat(copy.contains("More information about")).isFalse()
        expectThat(copy.contains("LOW INCOME TICKET")).isFalse()
        expectThat(copy.contains("Venue accessibility")).isFalse()
        expectThat(copy.contains("Booking fees apply")).isFalse()

        // no narratives section: a blank description, not a source that has stopped parsing
        expectThat(source.eventPageContent(pageOf(detailColumn("")))).isEqualTo("")
        // no detail column at all: the page is no longer the shape this reads
        expectThat(source.eventPageContent(pageOf("<main><p>Unusual Traffic Detected</p></main>"))).isEqualTo(null)
    }

    // Why the listing is checked rather than walked: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
    @Test
    fun `fails rather than reading the first page of a listing that has outgrown one`() {
        val failure = runCatching { BushHallGigsSource(fakeSite(pagination = "1 of 2")).latestGigs() }.exceptionOrNull()

        expectThat(failure?.message.orEmpty().contains("no longer all arrive in one page")).isTrue()
    }

    private fun onlyGigFrom(
        card: String = "",
        eventPageHead: String = """<meta property="og:image" content="https://c.ststat.net/content/entimg/tour/poster.jpg">""",
        statusMarkup: String = "",
    ): Gig = BushHallGigsSource(fakeSite(card = card, eventPageHead = eventPageHead, statusMarkup = statusMarkup))
        .latestGigs()
        .single()

    // One body answers both the listing request and the event-page request that follows it, the way
    // the other venues' tests do - the source reads different parts of it for each.
    private fun fakeSite(
        card: String = "",
        eventPageHead: String = """<meta property="og:image" content="https://c.ststat.net/content/entimg/tour/poster.jpg">""",
        statusMarkup: String = "",
        pagination: String = "1 of 1",
    ): HttpHandler {
        val body = """
            $eventPageHead
            <ul class="search-results results g-blocklist">
                <li class="g-blocklist-item"><article class="result-text">
                    <a class="g-blocklist-link g-blocklist-link-with-image" href="/event/liam-bailey/bush-hall/3600164" title="Liam Bailey">
                        <div class="g-blocklist-main">$card</div>
                        <span class="g-blocklist-sub-text">
                            <span class="event-title">Liam Bailey</span>
                            <span class="ev-listing-venue">Bush Hall, Shepherds Bush, London</span>
                            <span class="ev-listing-date">
                                <time datetime="Wednesday 23 September 2026">Wed 23 Sep 2026</time>,
                                <time datetime="23/09/2026">20:00 </time>
                            </span>
                        </span>
                        <span class="g-blocklist-action">$statusMarkup</span>
                    </a>
                    <aside class="g-blocklist-item-extended">
                        <span class="performing">Artists:</span><span><a href="/artist/liam-bailey/123">Liam Bailey</a></span>
                    </aside>
                </article></li>
            </ul>
            <nav class="pagination"><p>$pagination</p></nav>
            <div class="g-grid-col x9"><section class="g-ui-box">
                <section class="g-event-narratives"><div class="g-ui-box-content">An evening of something.</div></section>
            </section></div>
        """.trimIndent()
        return { Response(OK).body(body) }
    }
}
