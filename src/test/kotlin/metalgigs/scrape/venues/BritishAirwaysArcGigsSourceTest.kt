package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import kotlin.test.Test

// Why this reading surface and paging: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
// Why the poster comes off the event page: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
// Why a title is composed from the bill: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
class BritishAirwaysArcGigsSourceTest {

    // Only the two event pages the expectations read are committed, the other seven falling through
    // to the one page recorded per host (TrafficFixtures) - so those two posters are each gig's own.
    @Test
    fun `extracts gig events from British Airways ARC, paging past the listing's first six`() {
        val events = assertScrapesGigs(
            source = BritishAirwaysArcGigsSource(cachedClient(), JoinedActs.keptWhole),
            size = 9,
            first = Gig(
                GigId(britishAirwaysArc.id, GigUrl("https://www.britishairwaysarc.co.uk/event/the-damned/")),
                GigTitle("THE DAMNED / THE SAINTS ’73-’78"),
                GigDate(2026, 11, 27),
                PosterUrl("https://d1un96q5zviggl.cloudfront.net/app/uploads/2026/04/Damned-1000x562.be532459.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(britishAirwaysArc.id, GigUrl("https://www.britishairwaysarc.co.uk/event/orbital/")),
                GigTitle("ORBITAL"),
                GigDate(2027, 4, 24),
                PosterUrl("https://d1un96q5zviggl.cloudfront.net/app/uploads/2026/10/1000x562.3673c5a7.png"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )

        expectThat(events.map { it.id.url }.distinct()).hasSize(9)
    }

    // the card is the squash championship's, verbatim: a run of days prints no year at all
    @Test
    fun `takes a British Airways ARC start date from a run of days that prints no year`() {
        val html = """
            <article class="card event-card">
              <p class="card-event-date h5">
                <time datetime="2027-04-01">1 Apr</time> -
                <time datetime="2027-04-04">4 Apr</time>
              </p>
            </article>
        """.trimIndent()

        expectThat(source.startDateOf(pageOf(html).selectFirst("article")!!)).isEqualTo(GigDate(2027, 4, 1))
    }

    // the modal is verbatim from the footer every page of the site ends with
    @Test
    fun `scopes British Airways ARC's page text to the promoter's copy`() {
        val html = """
            <main>
              <section class="banner"><h1 class="page-title">THE DAMNED</h1></section>
              <div class="wysiwyg"><p>Half a century after igniting the fuse of the British punk explosion, The Damned announce a celebratory UK tour.</p></div>
            </main>
            <footer>
              <div class="modal-body">
                <h2 class="modal-title">Download the British Airways ARC Venue App</h2>
                <div class="modal-content-wysiwyg wysiwyg py-4"><p>Access your AXS Mobile ID Tickets and more</p></div>
              </div>
            </footer>
        """.trimIndent()

        expectThat(source.eventPageContent(pageOf(html)))
            .isEqualTo("Half a century after igniting the fuse of the British punk explosion, The Damned announce a celebratory UK tour.")
    }

    @Test
    fun `bills the acts a strapline brackets after the tour's name`() {
        val strapline = "FINAL DAMNATION 50 (WITH SUPPORT FROM: THE SAINTS ’73-’78)"

        expectThat(source.billedTitle(strapline, GigTitle("THE DAMNED"))).isEqualTo(GigTitle("THE DAMNED / THE SAINTS ’73-’78"))
        expectThat(source.billedLineup(strapline)).isEqualTo(Lineup(listOf("THE SAINTS ’73-’78")))
    }

    @Test
    fun `keeps the venue's title where the strapline names only the tour`() {
        expectThat(source.billedTitle("THE OTHER TOUR", GigTitle("ORBITAL"))).isEqualTo(null)
        expectThat(source.billedLineup("THE OTHER TOUR")).isEqualTo(Lineup.none)
    }

    private val source = BritishAirwaysArcGigsSource(noHttp, JoinedActs.keptWhole)
}
