package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.hasSize
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
// Why a source fails loudly: docs/adr/0002-a-source-fails-rather-than-publishing-something-plausible.md
class SquarespaceEventsGigsSourceTest {

    // its event pages are all empty, so unlike the other Squarespace venues the description comes off
    // the listing itself - asserted separately from first/last, since the excerpts run to a couple of
    // thousand characters each
    @Test
    fun `extracts gig events from The Fiddler's Elbow whos-playing page`() {
        val events = FiddlersElbowGigsSource(cachedClient()).latestGigs()
        events.forEach { println(it) }

        expectThat(events).hasSize(10)
        expectThat(events.first().copy(description = GigDescription(""))).isEqualTo(
            Gig(
                GigId(fiddlersElbow.id, GigUrl("https://www.thefiddlerselbow.co.uk/whos-playing/moonpunx-16-matinee1682026")),
                GigTitle("MoonPunx 16 Matinee"),
                GigDate(2026, 8, 16),
                PosterUrl("https://images.squarespace-cdn.com/content/v1/56eabd14b6aa60459af3a4f2/1786573507310-PG9FBW6TLI2B45R0QU3D/Unknown-2.png"),
                GigDescription(""),
            ),
        )
        expectThat(events.last().copy(description = GigDescription(""))).isEqualTo(
            Gig(
                GigId(fiddlersElbow.id, GigUrl("https://www.thefiddlerselbow.co.uk/whos-playing/neo-rockabilly-explosion-3-the-neutronz-wigsville-spliffs-dj-chris-setzer2692026")),
                GigTitle("NEO ROCKABILLY EXPLOSION #3 The Neutronz, Wigsville Spliffs, DJ Chris Setzer."),
                GigDate(2026, 9, 26),
                PosterUrl("https://images.squarespace-cdn.com/content/v1/56eabd14b6aa60459af3a4f2/1785973667820-T4D2VFAJ8AY9UAJQ4GHP/69613b4679576_event.jpeg"),
                GigDescription(""),
            ),
        )
        expectThat(events.all { it.id.url.value.startsWith("https://www.thefiddlerselbow.co.uk/whos-playing/") }).isTrue()
        // the whole reason the description comes off the listing: 80 chars is where the classifier
        // gives up on the text and judges the poster image instead
        expectThat(events.all { it.description.value.length > 80 }).isTrue()
        expectThat(events.first().description.value).contains("Steam Kittens are a punk band")
    }

    @Test
    fun `takes only the excerpt as a listing-described gig's text, not the item's own meta`() {
        val html = """
            <div class="eventlist eventlist--upcoming">
              <article class="eventlist-event eventlist-event--upcoming">
                <a href="/whos-playing/some-gig" class="eventlist-column-thumbnail"><img src="https://example.com/poster.jpg"></a>
                <h1 class="eventlist-title"><a href="/whos-playing/some-gig" class="eventlist-title-link">SOME GIG</a></h1>
                <time class="event-date" datetime="2026-09-01">Tuesday 1 September 2026</time>
                <div class="eventlist-excerpt"><p>Doom metal night with support.</p></div>
                <ul class="eventlist-meta event-meta">
                  <li class="eventlist-meta-item eventlist-meta-address">
                    <span class="eventlist-meta-address-line">1 Malden Road</span>
                    <a class="eventlist-meta-address-maplink">(map)</a>
                  </li>
                </ul>
                <a class="eventlist-button">View Event &#8594;</a>
              </article>
            </div>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }

        val events = SquarespaceEventsGigsSource(
            fakeClient,
            url = "https://example.com/whos-playing",
            venue = fiddlersElbow,
            descriptionFrom = SquarespaceDescription.ListingExcerpt,
        ).latestGigs()

        expectThat(events.single().description).isEqualTo(GigDescription("Doom metal night with support."))
    }

    @Test
    fun `skips a named Fiddler's Elbow gig whose card carries no thumbnail`() {
        val source = FiddlersElbowGigsSource(servingCardWithNoThumbnailAt("/whos-playing/s-for-sierra-ep-launch-party1992026"))

        expectThat(source.latestGigs()).isEmpty()
    }

    @Test
    fun `fails the whole Fiddler's Elbow listing when an unnamed gig's card carries no thumbnail`() {
        val source = FiddlersElbowGigsSource(servingCardWithNoThumbnailAt("/whos-playing/some-other-band1992026"))

        assertFailsWith<IllegalStateException> { source.latestGigs() }
    }

    private fun servingCardWithNoThumbnailAt(path: String): HttpHandler = { _ ->
        Response(OK).body(
            """
                <article class="eventlist-event eventlist-event--upcoming">
                  <a href="$path" class="eventlist-column-thumbnail content-fill" data-animation-role="image"></a>
                  <h1 class="eventlist-title"><a href="$path" class="eventlist-title-link">S for Sierra : EP Launch Party</a></h1>
                  <time class="event-date" datetime="2026-09-19">Saturday 19 September 2026</time>
                  <div class="eventlist-excerpt"><p>Join us for the release of our debut EP.</p></div>
                </article>
            """,
        )
    }

    // Both gigs are expected under one poster because every event page here is answered by the single
    // recorded page this host stands in with (TrafficFixtures) - so what it holds the scrape to is
    // that the poster came off the event page, the card's thumbnail differing on every card.
    @Test
    fun `extracts gig events from The Black Heart events page`() {
        assertScrapesGigs(
            source = TheBlackHeartGigsSource(cachedClient()),
            size = 50,
            first = Gig(
                GigId(theBlackHeart.id, GigUrl("https://www.ourblackheart.com/events/2026/8/8/you-win-again-gravity")),
                GigTitle("YOU WIN AGAIN GRAVITY"),
                GigDate(2026, 8, 8),
                PosterUrl("https://images.squarespace-cdn.com/content/v1/5486e6cde4b0d80114155bf4/5b8cf137-c089-48d8-b591-60989f951741/Necropolis_2027_IG_Feed_Poster_3rd_announcement.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theBlackHeart.id, GigUrl("https://www.ourblackheart.com/events/2027/3/19/necropolis-vol-iii")),
                GigTitle("NECROPOLIS VOL. III"),
                GigDate(2027, 3, 19),
                PosterUrl("https://images.squarespace-cdn.com/content/v1/5486e6cde4b0d80114155bf4/5b8cf137-c089-48d8-b591-60989f951741/Necropolis_2027_IG_Feed_Poster_3rd_announcement.jpg"),
                GigDescription(""),
            ),
        )
    }

    // The poster expected here is the recorded event page's, for the reason given above.
    @Test
    fun `extracts gig events from The Dome whatson page`() {
        assertScrapesGigs(
            source = DomeLondonGigsSource(cachedClient()),
            size = 70,
            first = Gig(
                GigId(theDome.id, GigUrl("https://www.domelondon.co.uk/whatson/08/08-battlesnake")),
                GigTitle("BATTLESNAKE"),
                GigDate(2026, 8, 8),
                PosterUrl("https://images.squarespace-cdn.com/content/v1/6708f569091ee6412723acb9/108ef8cb-7ffb-4247-ac70-8b48dda635ad/Draconian-2027-London-A3-poster.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theDome.id, GigUrl("https://www.domelondon.co.uk/whatson/03/07-draconian")),
                GigTitle("DRACONIAN"),
                GigDate(2027, 3, 7),
                PosterUrl("https://images.squarespace-cdn.com/content/v1/6708f569091ee6412723acb9/108ef8cb-7ffb-4247-ac70-8b48dda635ad/Draconian-2027-London-A3-poster.jpg"),
                GigDescription(""),
            ),
        )
    }

    // The Black Heart and The Dome share this same Squarespace "Events" template, and both delegate
    // to this scraper, so one fixture covers both. The meta block here is verbatim from a real
    // Black Heart page, where it outweighed the gig's own blurb on the shorter listings
    @Test
    fun `scopes Squarespace-venue page text to the content column, ignoring the event meta block`() {
        val html = """
            <nav><a>Home</a><a>About</a></nav>
            <article class="eventitem">
                <h1 class="eventitem-title">Doom Night</h1>
                <ul class="eventitem-meta event-meta">
                    <li class="eventitem-meta-date">Wednesday, August 12, 2026</li>
                    <li class="eventitem-meta-time">7:00 PM 11:00 PM <span>19:00 23:00</span></li>
                    <li class="eventitem-meta-address">The Black Heart 2 Greenland Place London, England, NW1 United Kingdom (map)</li>
                    <li class="eventitem-meta-export"><a>Google Calendar</a><a>ICS</a></li>
                </ul>
                <div class="eventitem-column-content"><p>Doom metal night!</p></div>
            </article>
            <footer><a>Instagram</a><a>Privacy Policy</a></footer>
        """.trimIndent()

        val source = SquarespaceEventsGigsSource(noHttp, url = "https://example.com/events", venue = theBlackHeart)
        val pageText = source.eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Greenland Place")).isEqualTo(false)
        expectThat(pageText.contains("19:00")).isEqualTo(false)
        expectThat(pageText.contains("Google Calendar")).isEqualTo(false)
        expectThat(pageText.contains("About")).isEqualTo(false)
        expectThat(pageText.contains("Instagram")).isEqualTo(false)
    }

    // A Bandcamp embed's fallback markup reaches the content column html-escaped rather than as
    // elements, so Jsoup's own text() decodes it to visible "<a href=...>" in the middle of the
    // gig's copy - three Black Heart descriptions in the log end that way. The link names the album
    // and the band, which is worth keeping, so the text is read a second time rather than dropped.
    @Test
    fun `reads a squarespace embed's escaped markup as the text it holds`() {
        val html = """
            <div class="eventitem-column-content">
                <p>MORAG TONG DRUIDESS OUTBACK</p>
                &lt;a href="https://moragtong.bandcamp.com/album/grieve-5"&gt;Grieve by Morag Tong&lt;/a&gt;
            </div>
        """.trimIndent()

        val source = SquarespaceEventsGigsSource(noHttp, url = "https://example.com/events", venue = theBlackHeart)
        val pageText = source.eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("MORAG TONG DRUIDESS OUTBACK")).isTrue()
        expectThat(pageText.contains("Grieve by Morag Tong")).isTrue()
        expectThat(pageText.contains("<a href")).isEqualTo(false)
        expectThat(pageText.contains("bandcamp.com")).isEqualTo(false)
    }

    // the Handgemeng page, whose bill is a paragraph per act and then two acts split by a <br>.
    // Flattening it loses the boundary a bill's acts are told apart by: "WARPSTORMER BIRDWITCH"
    // would read as one act, exactly as "ISHTAR TERRA" does.
    @Test
    fun `keeps the copy's own lines, so a bill's acts stay apart`() {
        val html = """
            <div class="eventitem-column-content">
                <p>London Doom Collective presents...</p>
                <p>HÄNDGEMENG</p>
                <p>Plus guests...</p>
                <p>WARPSTORMER<br>BIRDWITCH</p>
            </div>
        """.trimIndent()

        val source = SquarespaceEventsGigsSource(noHttp, url = "https://example.com/events", venue = theBlackHeart)

        expectThat(source.eventPageContent(pageOf(html))).isEqualTo(
            "London Doom Collective presents...\nHÄNDGEMENG\nPlus guests...\nWARPSTORMER\nBIRDWITCH",
        )
    }

    // The Necropolis Vol. III page, whose content column is a ticket button over the poster and nothing else.
    @Test
    fun `takes no copy from a squarespace ticket button`() {
        val html = """
            <div class="eventitem-column-content">
                <div class="sqs-block website-component-block sqs-block-button button-block"><div class="sqs-block-content">
                    <div class="sqs-block-button-container"><a class="sqs-block-button-element">buy tickets</a></div>
                </div></div>
                <div class="sqs-block image-block"><figure class="sqs-block-image-figure"><img src="poster.jpg"></figure></div>
            </div>
        """.trimIndent()

        val source = SquarespaceEventsGigsSource(noHttp, url = "https://example.com/events", venue = theBlackHeart)

        expectThat(source.eventPageContent(pageOf(html))).isEqualTo("")
    }

    // The Dome's Havok night: the band's press shot on the card, the night's poster in the copy.
    // Why the copy's image rather than the card's: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
    @Test
    fun `takes the poster from the event page rather than the listing card`() {
        val source = SquarespaceEventsGigsSource(
            servingEventPageContent(
                """
                <div class="sqs-block image-block sqs-block-image"><figure class="sqs-block-image-figure">
                    <img data-image="$poster" src="$poster" width="1080" height="1350">
                </figure></div>
                """,
            ),
            url = "https://example.com/events",
            venue = theDome,
        )

        expectThat(source.latestGigs().single().posterUrl).isEqualTo(PosterUrl(poster))
    }

    @Test
    fun `reads a lazy-loaded event page poster from data-image`() {
        val source = SquarespaceEventsGigsSource(
            servingEventPageContent(
                """
                <div class="sqs-block image-block sqs-block-image"><figure class="sqs-block-image-figure">
                    <img data-src="$poster" data-image="$poster" data-load="false">
                </figure></div>
                """,
            ),
            url = "https://example.com/events",
            venue = theDome,
        )

        expectThat(source.latestGigs().single().posterUrl).isEqualTo(PosterUrl(poster))
    }

    // A Black Heart gig written up in text alone, its artwork never posted, is one of the 47 listed.
    @Test
    fun `falls back to the listing card when the event page carries no image`() {
        val source = SquarespaceEventsGigsSource(
            servingEventPageContent("<p>Doors 7pm. Tickets on the door.</p>"),
            url = "https://example.com/events",
            venue = theDome,
        )

        expectThat(source.latestGigs().single().posterUrl).isEqualTo(PosterUrl(thumbnail))
    }

    @Test
    fun `fails the listing when neither the event page nor the card carries an image`() {
        val source = SquarespaceEventsGigsSource(
            servingEventPageContent("<p>Doors 7pm. Tickets on the door.</p>", thumbnail = null),
            url = "https://example.com/events",
            venue = theDome,
        )

        assertFailsWith<IllegalStateException> { source.latestGigs() }
    }

    private val poster = "https://images.squarespace-cdn.com/content/v1/6708f569/0ec3df6a/Havok-2026-London-4x5.jpg"
    private val thumbnail = "https://images.squarespace-cdn.com/content/v1/6708f569/1775581178961/Havok_2026.jpg"

    private fun servingEventPageContent(content: String, thumbnail: String? = this.thumbnail): HttpHandler = { request ->
        Response(OK).body(
            if (request.uri.path == "/events") """
                <article class="eventlist-event eventlist-event--upcoming">
                  <a href="/events/2026/9/19/havok" class="eventlist-column-thumbnail content-fill">
                    ${thumbnail?.let { """<img src="$it">""" } ?: ""}
                  </a>
                  <h1 class="eventlist-title"><a href="/events/2026/9/19/havok" class="eventlist-title-link">HAVOK</a></h1>
                  <time class="event-date" datetime="2026-09-19">Saturday 19 September 2026</time>
                </article>
            """
            else """<article class="eventitem"><div class="eventitem-column-content">$content</div></article>""",
        )
    }
}
