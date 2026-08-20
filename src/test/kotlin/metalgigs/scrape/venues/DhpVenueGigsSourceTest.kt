package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo
import java.time.LocalDate
import kotlin.test.Test

class DhpVenueGigsSourceTest {

    @Test
    fun `extracts gig events from The Garage live page`() {
        assertScrapesGigs(
            source = TheGarageGigsSource(cachedClient()),
            size = 43,
            first = Gig(
                GigId(theGarage.id, "https://www.thegarage.london/gigs/the-flatliners-a-wilhelm-scream-the-garage-lonodn-tickets-2026/"),
                GigTitle("THE FLATLINERS + A WILHELM SCREAM"),
                LocalDate.of(2026, 8, 22),
                PosterUrl("https://www.thegarage.london/wp-content/uploads/2026/03/Flatliners_2026_Ig-819x1024.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theGarage.id, "https://www.thegarage.london/gigs/black-altar-xxx-anniversary-show-the-garage-london-tickets-2026/"),
                GigTitle("BLACK ALTAR - XXX ANNIVERSARY SHOW"),
                LocalDate.of(2026, 10, 31),
                PosterUrl("https://www.thegarage.london/wp-content/uploads/2026/07/XXXYears-Poster-4-insta-819x1024.jpg"),
                GigDescription(""),
            ),
            urlPrefix = "https://www.thegarage.london/gigs/",
        )
    }

    @Test
    fun `extracts gig events from The Grace whats-on page`() {
        assertScrapesGigs(
            source = TheGraceGigsSource(cachedClient()),
            size = 48,
            first = Gig(
                GigId(theGrace.id, "https://www.thegrace.london/gigs/flamebearer-the-grace-london-tickets-2026/"),
                GigTitle("FLAMEBEARER"),
                LocalDate.of(2026, 8, 14),
                PosterUrl("https://www.thegrace.london/wp-content/uploads/2026/05/FLAMEBEARER_IGNITER_ALBUM_LAUNCH_POSTER_SQUARE_v3_MED_RES_RGB-1-1024x1024.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theGrace.id, "https://www.thegrace.london/gigs/dreamdnvr-the-grace-london-tickets-2026/"),
                GigTitle("DREAMDNVR"),
                LocalDate.of(2026, 10, 31),
                PosterUrl("https://www.thegrace.london/wp-content/uploads/2026/05/PRESS-PHOTO-DD-3-1-1024x683.jpg"),
                GigDescription(""),
            ),
            urlPrefix = "https://www.thegrace.london/gigs/",
        )
    }

    // Verbatim shape from The Garage's listing, which printed "Image not found" where When Chai
    // Met Toast's poster should have been while the gig's own page rendered it.
    @Test
    fun `takes a DHP gig's poster from its page when the listing card has none`() {
        val html = """
            <div class="card card--full">
              <div class="card__strip">
                <h6 class="card__strip-heading">Fri.14.Aug.26</h6>
              </div>
              <div class="card__grid">
                <a href="https://example.com/gigs/imageless-gig/" class="card__grid-media media"><p>Image not found</p></a>
                <a href="https://example.com/gigs/imageless-gig/" class="card__heading">IMAGELESS GIG</a>
              </div>
            </div>
            <img data-lazy-src="https://example.com/article-poster.jpg" class="img img--bg article-image__bg" />
            <section class="single-article single-article--contains-list">
              <div class="single-article__content"><p>Doors 7pm.</p></div>
            </section>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }

        val events = DhpVenueGigsSource(fakeClient, url = "https://example.com/whats-on/", venue = Venue(VenueId("some-venue"), "Some Venue")).latestGigs()

        expectThat(events.single().posterUrl).isEqualTo(PosterUrl("https://example.com/article-poster.jpg"))
    }

    @Test
    fun `takes a sold-out DHP gig's url from its notification, since its heading isn't a link`() {
        val html = """
            <div class="card card--full card--contains-notification">
              <mark class="notification card__notification">
                <a href="https://example.com/gigs/sold-out-gig/"><h4 class="notification__title">Gig Sold Out</h4></a>
              </mark>
              <div class="card__strip">
                <h6 class="card__strip-heading">Live</h6>
                <h6 class="card__strip-heading card__strip-heading--last">Sat.03.Oct.26</h6>
              </div>
              <div class="card__grid">
                <div class="card__grid-media media">
                  <img data-lazy-src="https://example.com/poster.jpg" />
                </div>
                <h4 class="card__heading">SOLD OUT GIG</h4>
              </div>
            </div>
            <!-- the same body answers the event-page request, which now has to yield a description -->
            <section class="single-article single-article--contains-list">
              <div class="single-article__content"><p>Sold out gig, doors 7pm.</p></div>
            </section>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }

        val events = DhpVenueGigsSource(fakeClient, url = "https://example.com/whats-on/", venue = Venue(VenueId("some-venue"), "Some Venue")).latestGigs()

        expectThat(events).containsExactly(
            Gig(
                GigId(VenueId("some-venue"), "https://example.com/gigs/sold-out-gig/"),
                GigTitle("SOLD OUT GIG"),
                LocalDate.of(2026, 10, 3),
                PosterUrl("https://example.com/poster.jpg"),
                GigDescription("Sold out gig, doors 7pm."),
            ),
        )
    }

    // The Garage and The Grace (both DHP Family, sharing DhpVenueGigsSource) share this same
    // WordPress theme - the obvious ".single-article" also matches its own outer wrapper div,
    // which would double every word of text, so this is scoped to the more specific inner class.
    // The meta bar, list and CTA below are verbatim from a real listing, which they made up most of
    @Test
    fun `scopes DHP-venue page text to the content block, dropping the meta bar and the CTA`() {
        val html = """
            <nav><a>Home</a><a>News</a></nav>
            <div class="section single-article">
                <section class="single-article single-article--contains-list">
                    <div class="single-article__title-bar"><h1>Doom Night</h1> The Garage, London</div>
                    <div class="single-article__meta-bar"><a>BUY TICKETS</a> Sat 15th August 2026 7:00 pm £20</div>
                    <article class="single-article__content">
                        <p>Doom metal night!</p>
                        <p><em>For more events, check out what's on here.</em></p>
                    </article>
                    <ul class="single-article__list"><li>Date: Sat 15th August 2026</li><li>Doors Open: 7:00 pm</li><li>On Sale: Tickets Open</li><li>Price: £20</li></ul>
                </section>
            </div>
            <section><h2>ON SPOTIFY</h2></section>
        """.trimIndent()

        val source = DhpVenueGigsSource(noHttp, url = "https://example.com/live/", venue = theGarage)
        val pageText = source.eventPageContent(pageOf(html))!!

        expectThat(pageText).isEqualTo("Doom metal night!")
    }
}
