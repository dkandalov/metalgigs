package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.time.LocalDate
import kotlin.test.Test

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
                GigId(fiddlersElbow.id, "https://www.thefiddlerselbow.co.uk/whos-playing/moonpunx-16-matinee1682026"),
                GigTitle("MoonPunx 16 Matinee"),
                LocalDate.of(2026, 8, 16),
                PosterUrl("https://images.squarespace-cdn.com/content/v1/56eabd14b6aa60459af3a4f2/1786573507310-PG9FBW6TLI2B45R0QU3D/Unknown-2.png"),
                GigDescription(""),
            ),
        )
        expectThat(events.last().copy(description = GigDescription(""))).isEqualTo(
            Gig(
                GigId(fiddlersElbow.id, "https://www.thefiddlerselbow.co.uk/whos-playing/neo-rockabilly-explosion-3-the-neutronz-wigsville-spliffs-dj-chris-setzer2692026"),
                GigTitle("NEO ROCKABILLY EXPLOSION #3 The Neutronz, Wigsville Spliffs, DJ Chris Setzer."),
                LocalDate.of(2026, 9, 26),
                PosterUrl("https://images.squarespace-cdn.com/content/v1/56eabd14b6aa60459af3a4f2/1785973667820-T4D2VFAJ8AY9UAJQ4GHP/69613b4679576_event.jpeg"),
                GigDescription(""),
            ),
        )
        expectThat(events.all { it.id.url.startsWith("https://www.thefiddlerselbow.co.uk/whos-playing/") }).isTrue()
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
    fun `extracts gig events from The Black Heart events page`() {
        assertScrapesGigs(
            source = TheBlackHeartGigsSource(cachedClient()),
            size = 50,
            first = Gig(
                GigId(theBlackHeart.id, "https://www.ourblackheart.com/events/2026/8/8/you-win-again-gravity"),
                GigTitle("YOU WIN AGAIN GRAVITY"),
                LocalDate.of(2026, 8, 8),
                PosterUrl("https://images.squarespace-cdn.com/content/v1/5486e6cde4b0d80114155bf4/1782745761879-UVSUIG341XJIY3MEB9MI/LBPHOTO%2B-%2B%2BYou%2BWin%2BAgain%2BGravity%2B-%2BPromo%2B-%2B20.10.2024%2B6.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theBlackHeart.id, "https://www.ourblackheart.com/events/2027/3/19/necropolis-vol-iii"),
                GigTitle("NECROPOLIS VOL. III"),
                LocalDate.of(2027, 3, 19),
                PosterUrl("https://images.squarespace-cdn.com/content/v1/5486e6cde4b0d80114155bf4/1781025655512-MHR6PMWPOOE3TJFOSWAB/Necropolis_2027_IG_Feed_Poster_2nd_announcement%2B%25281%2529.jpg"),
                GigDescription(""),
            ),
            urlPrefix = "https://www.ourblackheart.com/events/",
        )
    }

    @Test
    fun `extracts gig events from The Dome whatson page`() {
        assertScrapesGigs(
            source = DomeLondonGigsSource(cachedClient()),
            size = 70,
            first = Gig(
                GigId(theDome.id, "https://www.domelondon.co.uk/whatson/08/08-battlesnake"),
                GigTitle("BATTLESNAKE"),
                LocalDate.of(2026, 8, 8),
                PosterUrl("https://images.squarespace-cdn.com/content/v1/6708f569091ee6412723acb9/1777381588492-CAQQZA5RRSD026668882/Cathedral%2BColour.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theDome.id, "https://www.domelondon.co.uk/whatson/03/07-draconian"),
                GigTitle("DRACONIAN"),
                LocalDate.of(2027, 3, 7),
                PosterUrl("https://images.squarespace-cdn.com/content/v1/6708f569091ee6412723acb9/1771509016965-K3W9K2G4J853EZ97RETL/Draconian+done-56+%28low+res%29.jpg"),
                GigDescription(""),
            ),
            urlPrefix = "https://www.domelondon.co.uk/whatson/",
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
}
