package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo
import kotlin.test.Test

// Why one page is the whole listing: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
// Why the poster is the upload: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
class TroxyGigsSourceTest {

    // 32 cards on the one page, against 43 events in the site's WordPress api with 11 of them past
    @Test
    fun `extracts gig events from Troxy's what's on page`() {
        val events = assertScrapesGigs(
            source = TroxyGigsSource(cachedClient()),
            size = 32,
            first = Gig(
                GigId(troxy.id, GigUrl("https://troxy.co.uk/event/kings-of-gaana/")),
                GigTitle("Kings Of Gaana"),
                GigDate(2026, 12, 6),
                PosterUrl("https://s1.ticketm.net/dam/e/ec4/c3764ec1-57b9-4fc8-b595-ec5d92353ec4_SOURCE"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(troxy.id, GigUrl("https://troxy.co.uk/event/purple-disco-machine/")),
                GigTitle("Purple Disco Machine"),
                GigDate(2027, 11, 20),
                PosterUrl("https://s1.ticketm.net/dam/e/bf3/7a5f15ab-a4e8-4174-9b69-56bd7648bbf3_SOURCE"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )

        expectThat(events.map { it.id }.toSet().size).isEqualTo(32)
        expectThat(events.count { it.posterUrl.value.endsWith("_SOURCE") }).isEqualTo(32)
        expectThat(events.single { it.id.url == GigUrl("https://troxy.co.uk/event/thrown/") }.date).isEqualTo(GigDate(2026, 12, 13))
        expectThat(events.filter { it.status != null }.map { it.title to it.status })
            .containsExactly(GigTitle("Madra Salach - SOLD OUT") to GigStatus.SoldOut)
    }

    @Test
    fun `reads a card's date day first, writes a sold out gig's status into its title, and leaves one on sale alone`() {
        val listing = card("/event/on-sale/", "ON SALE", "13-12-26", "onsale") +
            card("/event/sold-out/", "SOLD OUT GIG", "02-01-27", "soldout") +
            card("/event/soon/", "SOON", "03-02-27", "onsale-soon")
        val client: HttpHandler = { request ->
            Response(OK).body(if (request.uri.path == "/whats-on/") listing else """<div id="event-description"><p>Doom night.</p></div>""")
        }

        val events = TroxyGigsSource(client).latestGigs()

        expectThat(events.map { Triple(it.title, it.date, it.status) }).containsExactly(
            Triple(GigTitle("ON SALE"), GigDate(2026, 12, 13), null),
            Triple(GigTitle("SOLD OUT GIG - SOLD OUT"), GigDate(2027, 1, 2), GigStatus.SoldOut),
            Triple(GigTitle("SOON"), GigDate(2027, 2, 3), null),
        )
    }

    // the ticket terms are thrown's, and the cards under the article are the "Upcoming events" every
    // event page ends on
    @Test
    fun `scopes Troxy page text to the gig's own copy, without its ticket terms or the other events' cards`() {
        val html = """
            <article class="troxy-event">
              <h1 class="troxy-event__title">THROWN</h1>
              <div id="event-description" class="troxy-event__description-wrap">
                <p>thrown is a Swedish hardcore/metalcore band from Stockholm.</p>
                <p><strong>Please Note:</strong><br>14+ A max of 6 tickets per person and per household applies. Tickets in excess of 6 will be cancelled.</p>
                <p id="read-more" class="troxy-event__description__read-more"><a href="#" class="read-more-button">Read more</a></p>
              </div>
              <div class="troxy-event__schedule"><h2>Schedule</h2><ul><li><strong>18:00</strong> - Doors Open</li></ul></div>
            </article>
            <section class="troxy-event-upcoming-list"><h2>Upcoming events</h2>
              <article class="troxy-whatson-card"><h3 class="troxy-whatson-card__lower__title">Kings Of Gaana</h3></article>
            </section>
        """.trimIndent()

        expectThat(TroxyGigsSource(noHttp).eventPageContent(pageOf(html))).isEqualTo("thrown is a Swedish hardcore/metalcore band from Stockholm.")
    }

    // Kings Of Gaana's page on 2026-11-26, the one of 32 whose promoter wrote nothing
    @Test
    fun `reads a Troxy page holding only its ticket terms as a blank description`() {
        val html = """
            <div id="event-description">
              <p><strong>Please Note:</strong><br>Over 14s only. A max of 8 tickets per person and per household applies.</p>
              <p id="read-more"><a href="#">Read more</a></p>
            </div>
        """.trimIndent()

        expectThat(TroxyGigsSource(noHttp).eventPageContent(pageOf(html))).isEqualTo("")
    }

    @Test
    fun `swaps Ticketmaster's crop for the upload behind it, leaving an upload and any other url alone`() {
        val upload = "https://s1.ticketm.net/dam/e/bb9/ce74eef7-67dc-45c0-a125-1b572069cbb9_SOURCE"

        expectThat(ticketmasterUpload("https://s1.ticketm.net/dam/e/bb9/ce74eef7-67dc-45c0-a125-1b572069cbb9_TABLET_LANDSCAPE_LARGE_16_9.jpg")).isEqualTo(upload)
        expectThat(ticketmasterUpload(upload)).isEqualTo(upload)
        expectThat(ticketmasterUpload("https://troxy.co.uk/wp-content/uploads/2026/05/poster_LARGE.jpg")).isEqualTo("https://troxy.co.uk/wp-content/uploads/2026/05/poster_LARGE.jpg")
    }

    private fun card(href: String, title: String, date: String, status: String) = """
        <article class="troxy-whatson-card troxy-whatson-card--event_default">
            <a href="https://troxy.co.uk$href">
                <div class="troxy-whatson-card__upper__img"><img src="https://s1.ticketm.net/dam/e/bb9/ce74eef7-67dc-45c0-a125-1b572069cbb9_SOURCE"></div>
                <p class="troxy-whatson-card__upper__date-item"><time datetime="$date">${date.replace('-', '.')}</time></p>
                <p><span class="troxy-status-pill" data-status="$status"></span></p>
                <h3 class="troxy-whatson-card__lower__title">$title</h3>
            </a>
        </article>
    """.trimIndent()
}
