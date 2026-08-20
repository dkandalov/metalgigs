package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DhpVenueGigsSourceTest {

    @Test
    fun `extracts gig events from The Garage live page`() {
        assertScrapesGigs(
            source = TheGarageGigsSource(cachedClient()),
            size = 80,
            first = Gig(
                GigId(theGarage.id, "https://www.thegarage.london/gigs/the-flatliners-a-wilhelm-scream-the-garage-lonodn-tickets-2026/"),
                GigTitle("THE FLATLINERS + A WILHELM SCREAM"),
                GigDate(2026, 8, 22),
                PosterUrl("https://www.thegarage.london/wp-content/uploads/2026/03/Flatliners_2026_Ig-819x1024.jpg"),
                GigDescription(""),
            ),
            // six months past 31 Oct 2026, where the listing page's own markup ends - only the
            // guide walk reaches this, and the 80 above is against the 43 without it
            last = Gig(
                GigId(theGarage.id, "https://www.thegarage.london/gigs/st-lundi/"),
                GigTitle("ST LUNDI"),
                GigDate(2027, 4, 1),
                PosterUrl("https://www.thegarage.london/wp-content/uploads/2026/08/ST-LUNDI-LONDON-SPECIFIC-819x1024.jpg"),
                GigDescription(""),
            ),
            urlPrefix = "https://www.thegarage.london/gigs/",
        )
    }

    @Test
    fun `extracts gig events from The Grace whats-on page`() {
        assertScrapesGigs(
            source = TheGraceGigsSource(cachedClient()),
            size = 72,
            first = Gig(
                GigId(theGrace.id, "https://www.thegrace.london/gigs/flamebearer-the-grace-london-tickets-2026/"),
                GigTitle("FLAMEBEARER"),
                GigDate(2026, 8, 14),
                PosterUrl("https://www.thegrace.london/wp-content/uploads/2026/05/FLAMEBEARER_IGNITER_ALBUM_LAUNCH_POSTER_SQUARE_v3_MED_RES_RGB-1-1024x1024.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theGrace.id, "https://www.thegrace.london/gigs/nontaines-d-c-the-grace-london-tickets-2026/"),
                GigTitle("NONTAINES D.C."),
                GigDate(2027, 1, 19),
                PosterUrl("https://www.thegrace.london/wp-content/uploads/2026/06/Nontaines-Sep-26-Grace-Square-1-1024x1024.jpg"),
                GigDescription(""),
            ),
            urlPrefix = "https://www.thegrace.london/gigs/",
        )
    }

    // The endpoint answers past the end of the guide too, with months that have no cards in them,
    // so what stops the walk is the range the listing declares - here ending November 2026, which
    // the single call reaches.
    @Test
    fun `walks the gig guide past the months the listing renders, up to the end it declares`() {
        val listing = """
            <div class="js-guide-container">
                <input type="hidden" name="guide_post_type" value="gigs">
                <input type="hidden" name="guide_post_type_date_field" value="gig_date">
                <input type="hidden" name="guide_end_year" value="2026">
                <input type="hidden" name="guide_end_year_end_month" value="11">
                <div class="guide__month" data-month="October" data-year="2026" data-month-number="10">
                    <div class="card card--full">
                        <h6 class="card__strip-heading">Sat.31.Oct.26</h6>
                        <div class="card__grid-media"><img data-lazy-src="https://example.com/october.jpg" /></div>
                        <a href="/gigs/october-gig/" class="card__heading">OCTOBER GIG</a>
                    </div>
                </div>
            </div>
        """.trimIndent()
        val november = """
            <div class="guide__month" data-month="November" data-year="2026" data-month-number="11">
                <div class="card card--full">
                    <h6 class="card__strip-heading">Fri.06.Nov.26</h6>
                    <div class="card__grid-media"><img data-lazy-src="https://example.com/november.jpg" /></div>
                    <a href="/gigs/november-gig/" class="card__heading">NOVEMBER GIG</a>
                </div>
            </div>
        """.trimIndent()
        val eventPage = """
            <section class="single-article single-article--contains-list">
                <div class="single-article__content"><p>Doors 7pm.</p></div>
            </section>
        """.trimIndent()
        val requests = mutableListOf<Request>()
        val fakeClient: HttpHandler = { request ->
            requests += request
            Response(OK).body(
                when {
                    request.method == POST -> november
                    request.uri.path == "/live/" -> listing
                    else -> eventPage
                }
            )
        }

        val gigs = DhpVenueGigsSource(fakeClient, url = "https://example.com/live/", venue = theGarage).latestGigs()

        expectThat(gigs.map { it.title }).containsExactly(GigTitle("OCTOBER GIG"), GigTitle("NOVEMBER GIG"))
        expectThat(requests.single { it.method == POST }) {
            get { uri.toString() }.isEqualTo("https://example.com/wp-content/themes/dhp/includes/ajax/ajax_guide.php")
            get { header("content-type") }.isEqualTo("application/x-www-form-urlencoded")
            get { bodyString() }
                .isEqualTo("guide_post_type=gigs&guide_post_type_date_field=gig_date&guide_prev_month=October&guide_prev_year=2026")
        }
    }

    // Verbatim shape of what the endpoint answers a call it can't read the parameters of: 200,
    // and three months dated year 0. Followed, that is the same call again for ever, so the walk
    // has to refuse an answer that doesn't page forward rather than ask it to continue from one.
    @Test
    fun `fails rather than walking on when the guide answers with months no later than the one asked for`() {
        val listing = """
            <div class="js-guide-container">
                <input type="hidden" name="guide_post_type" value="gigs">
                <input type="hidden" name="guide_post_type_date_field" value="gig_date">
                <input type="hidden" name="guide_end_year" value="2027">
                <input type="hidden" name="guide_end_year_end_month" value="6">
                <div class="guide__month" data-month="October" data-year="2026" data-month-number="10"></div>
            </div>
        """.trimIndent()
        val yearZero = """
            <div class="visually-hidden guide__month" data-month="September" data-year="0" data-month-number="8" data-has-posts="false"></div>
        """.trimIndent()
        val fakeClient: HttpHandler = { request -> Response(OK).body(if (request.method == POST) yearZero else listing) }

        val error = assertFailsWith<IllegalStateException> {
            DhpVenueGigsSource(fakeClient, url = "https://example.com/live/", venue = theGarage).latestGigs()
        }

        expectThat(error.message!!).contains("October 2026").and { contains("September 0") }
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

        val events = DhpVenueGigsSource(fakeClient, url = "https://example.com/whats-on/", venue = someVenue).latestGigs()

        expectThat(events.single().posterUrl).isEqualTo(PosterUrl("https://example.com/article-poster.jpg"))
    }

    @Test
    fun `stands the venue's own image in for a DHP gig with no poster on its card or its page`() {
        val fakeClient: HttpHandler = { Response(OK).body(artworklessGig) }

        val events = DhpVenueGigsSource(
            fakeClient,
            url = "https://example.com/whats-on/",
            venue = someVenue,
            venueImage = PosterUrl("https://example.com/the-venue.jpg"),
        ).latestGigs()

        expectThat(events.single().posterUrl).isEqualTo(PosterUrl("https://example.com/the-venue.jpg"))
    }

    // The Grace runs the same scraper off a site with no house image to stand in, so there the
    // absence still means the venue's whole listing fails rather than a gig quietly going missing.
    @Test
    fun `fails on a DHP gig with no poster anywhere when its venue has no image of its own`() {
        val fakeClient: HttpHandler = { Response(OK).body(artworklessGig) }

        val error = assertFailsWith<IllegalStateException> {
            DhpVenueGigsSource(fakeClient, url = "https://example.com/whats-on/", venue = someVenue).latestGigs()
        }

        expectThat(error.message!!).contains("https://example.com/gigs/artworkless-gig/")
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

        val events = DhpVenueGigsSource(fakeClient, url = "https://example.com/whats-on/", venue = someVenue).latestGigs()

        expectThat(events).containsExactly(
            Gig(
                GigId(VenueId("some-venue"), "https://example.com/gigs/sold-out-gig/"),
                GigTitle("SOLD OUT GIG"),
                GigDate(2026, 10, 3),
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

    private val someVenue = Venue(VenueId("some-venue"), "Some Venue")

    // Verbatim shape of a gig announced before its artwork exists - The Garage's Northside on
    // 21 Nov 2026 - where the event page prints the same "Image not found" as the card, so the
    // hero the card's own answer is checked against has nothing in it either.
    private val artworklessGig = """
        <div class="card card--full">
          <div class="card__strip">
            <h6 class="card__strip-heading">Sat.21.Nov.26</h6>
          </div>
          <div class="card__grid">
            <a href="https://example.com/gigs/artworkless-gig/" class="card__grid-media media"><p>Image not found</p></a>
            <a href="https://example.com/gigs/artworkless-gig/" class="card__heading">ARTWORKLESS GIG</a>
          </div>
        </div>
        <header class="article-image"><span class="media media--article"><p>Image not found</p></span></header>
        <section class="single-article single-article--contains-list">
          <div class="single-article__content"><p>Doors 7pm.</p></div>
        </section>
    """.trimIndent()
}
