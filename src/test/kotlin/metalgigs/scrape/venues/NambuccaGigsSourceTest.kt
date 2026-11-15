package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo
import strikt.assertions.isNull
import strikt.assertions.isTrue
import kotlin.test.Test

// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
// Why the poster is found this way: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
// Why the date is read this way: docs/adr/0010-a-date-is-read-per-venue-and-a-missing-year-is-inferred.md
class NambuccaGigsSourceTest {

    // every gig carries the one recorded event page's poster, the listing itself having none
    @Test
    fun `extracts gig events from Nambucca's upcoming events page`() {
        val poster = PosterUrl("https://nambuccalondon.co.uk/wp-content/uploads/2026/11/860224164_1785884910211993_1942222572879509274_n-1.jpg")
        val events = assertScrapesGigs(
            source = NambuccaGigsSource(cachedClient(), JoinedActs.keptWhole, year = 2026),
            size = 14,
            first = Gig(
                GigId(nambucca.id, GigUrl("https://nambuccalondon.co.uk/js_events/some-weird-sin-simon-and-mauro-dj-set/")),
                GigTitle("Some Weird Sin – Simon and Mauro DJ Set"),
                GigDate(2026, 11, 20),
                poster,
                GigDescription(""),
                Lineup.none,
            ),
            last = Gig(
                GigId(nambucca.id, GigUrl("https://nambuccalondon.co.uk/js_events/fallen-rituals-tour/")),
                GigTitle("Fallen Rituals Tour"),
                GigDate(2027, 3, 20),
                poster,
                GigDescription(""),
                Lineup.none,
            ),
        )

        // the listing crosses the new year between these two, with no year printed on either
        expectThat(events.takeLast(3).map { it.date })
            .containsExactly(GigDate(2026, 12, 13), GigDate(2027, 1, 23), GigDate(2027, 3, 20))
    }

    @Test
    fun `rolls over the year when Nambucca gigs cross into an earlier month`() {
        val html = """
            <ul class="events_list">
                ${card("dec-gig", "Dec", 12)}
                ${card("jan-gig", "Jan", 23)}
                ${card("mar-gig", "Mar", 20)}
            </ul>
            <div class="event_right"><img itemprop="image" src="https://example.com/poster.jpg"></div>
            <div itemprop="description">Doom night, doors 7pm.</div>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }

        val events = NambuccaGigsSource(fakeClient, JoinedActs.keptWhole, year = 2026).latestGigs()

        expectThat(events.map { it.date })
            .containsExactly(GigDate(2026, 12, 12), GigDate(2027, 1, 23), GigDate(2027, 3, 20))
    }

    // Kate Gore's page, its video and its artwork side by side
    @Test
    fun `takes the widest of the artwork's sizes, past a video embedded beside it`() {
        val page = pageOf("""
            <div class="event_right">
                <div class="lc_embed_video_container_full"><iframe src="https://www.youtube.com/embed/abc123?feature=oembed"></iframe></div>
                <img width="950" height="950" src="https://nambuccalondon.co.uk/wp-content/uploads/2026/10/s-1024x1024.png" itemprop="image"
                     srcset="https://nambuccalondon.co.uk/wp-content/uploads/2026/10/s-1024x1024.png 1024w, https://nambuccalondon.co.uk/wp-content/uploads/2026/10/s-300x300.png 300w, https://nambuccalondon.co.uk/wp-content/uploads/2026/10/s.png 1414w">
            </div>
        """.trimIndent())

        expectThat(NambuccaGigsSource(noHttp, JoinedActs.keptWhole, year = 2026).posterOn(page))
            .isEqualTo("https://nambuccalondon.co.uk/wp-content/uploads/2026/10/s.png")
    }

    // Tokyo Taboo's page, which has a video where its artwork would be and no image anywhere
    @Test
    fun `takes the video's still where a page has no artwork`() {
        val page = pageOf("""
            <div class="event_right">
                <div class="lc_embed_video_container_full">
                    <iframe title="Tokyo Taboo - WINGS (Official Music Video)" width="950" height="534" src="https://www.youtube.com/embed/CtM4L4M8Pyk?feature=oembed"></iframe>
                </div>
            </div>
        """.trimIndent())

        expectThat(NambuccaGigsSource(noHttp, JoinedActs.keptWhole, year = 2026).posterOn(page))
            .isEqualTo("https://i.ytimg.com/vi/CtM4L4M8Pyk/hqdefault.jpg")
    }

    @Test
    fun `finds no poster on a page with neither artwork nor video`() {
        val page = pageOf("""<img src="https://nambuccalondon.co.uk/wp-content/uploads/2026/07/title-1.png" class="custom-logo"><div class="event_right"></div>""")

        expectThat(NambuccaGigsSource(noHttp, JoinedActs.keptWhole, year = 2026).posterOn(page)).isNull()
    }

    @Test
    fun `scopes Nambucca page text to the event's description, ignoring its details and footer`() {
        val html = """
            <h1 class="entry-title">My Endless Winter + CRY-99</h1>
            <div class="event_short_details">
                <div class="lc_event_entry" itemprop="startDate" content="2026-11-20T19:00:00+00:00">November 20, 2026</div>
                <span itemprop="address"> 596 Holloway Road, N7 6LB </span>
            </div>
            <div class="lc_event_entry event_promo_btns"><a href="http://www.skiddle.com/e/43122110">Tickets</a></div>
            <div itemprop="description" class="event_description">
                <div dir="auto">My Endless Winter come to London for the first time as part of The Misery Tour!</div>
                <div dir="auto">Londoners CRY-99 (emo gaze-pop) open the show!</div>
            </div>
            <footer>Copyright © 2026 Nambucca</footer>
        """.trimIndent()

        val pageText = NambuccaGigsSource(noHttp, JoinedActs.keptWhole, year = 2026).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("as part of The Misery Tour!")).isTrue()
        expectThat(pageText.contains("CRY-99 (emo gaze-pop)")).isTrue()
        expectThat(pageText.contains("Holloway Road")).isEqualTo(false)
        expectThat(pageText.contains("Tickets")).isEqualTo(false)
        expectThat(pageText.contains("Copyright")).isEqualTo(false)
    }

    private fun card(slug: String, month: String, day: Int) = """
        <li class="single_event_list no_event_image" data-evmonth="$month">
            <a href="https://nambuccalondon.co.uk/js_events/$slug/">
                <div class="eventlist_day classic_ev_date"> $day </div>
                <div class="eventlist_month classic_ev_date">$month</div>
                <div class="event_list_title">$slug</div>
            </a>
        </li>
    """

    @Test
    fun `takes a gig's lineup from a heading that bills its acts`() {
        val gigs = NambuccaGigsSource(cachedClient(), JoinedActs.keptWhole, year = 2026).latestGigs()

        expectThat(gigs.first { it.title == GigTitle("My Endless Winter + CRY-99") }.lineup).isEqualTo(Lineup(listOf("My Endless Winter", "CRY-99")))
    }
}
