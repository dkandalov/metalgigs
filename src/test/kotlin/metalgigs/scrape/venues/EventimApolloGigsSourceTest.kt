package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test

class EventimApolloGigsSourceTest {

    @Test
    fun `extracts gig events from Eventim Apollo's events page`() {
        val events = assertScrapesGigs(
            source = EventimApolloGigsSource(cachedClient()),
            size = 83,
            first = Gig(
                GigId(eventimApollo.id, "https://www.eventimapollo.com/events/venue-tours"),
                GigTitle("Eventim Apollo OPEN: Venue Tours"),
                GigDate(2026, 8, 16),
                PosterUrl("https://aeg-media-assets.b-cdn.net/eventim/images/0e5e0082-1ed9-4180-97a0-5cb66a922ce7.jpg?width=768&height=768&focus_crop=1200,1200,0.5,0.5"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(eventimApollo.id, "https://www.eventimapollo.com/events/il-volo"),
                GigTitle("Il Volo"),
                GigDate(2027, 11, 5),
                PosterUrl("https://aeg-media-assets.b-cdn.net/eventim/images/IL-VOLO-1080x1080-copy-1.jpg?width=768&height=768&focus_crop=1080,1080,0.5,0.5"),
                GigDescription(""),
            ),
            urlPrefix = "https://www.eventimapollo.com/events/",
        )

        // the whole listing arrives in one page, so a size assertion is the only thing standing
        // between a month bar that starts navigating and a silently truncated listing
        expectThat(events.map { it.id.url }.distinct()).hasSize(83)
        // both date shapes are exercised by the two cards above: the first is a run of dates taking
        // its start, the last a single day. The listing runs from this August into late 2027.
        expectThat(events.map { it.date }.min()).isEqualTo(GigDate(2026, 8, 16))
        expectThat(events.filter { it.date.year == 2027 }).hasSize(24)
    }

    // the on-sale line, the buy buttons and the poster's caption are all inside the same hero as the
    // gig's copy, and on a listing with one sentence of copy they are most of its text
    @Test
    fun `takes Eventim Apollo's gig copy without the hero's ticketing furniture`() {
        val html = """
            <section class="event-hero bg-eventim-navy">
                <h2 class="h2 variable-color event-hero__title">Wilco</h2>
                <p class="variable-color" data-hide-on-date="2026-02-27T10:00:00+00:00">TICKETS ON SALE: 27 Feb 2026 AT 10:00am</p>
                <div class="mt-sm">
                    <div class="inline-block"><a class="btn btn--tickets buy-ticket">Buy tickets</a></div>
                </div>
                <div class="variable-color mt-sm">
                    <p><strong>Wilco </strong>- A Tour With Wilco is coming to Eventim Apollo this August.</p>
                    <p>For 30 years, Wilco has been a pioneering force in independent music.</p>
                </div>
                <div class="col-span-12 md:col-span-5 hidden md:block">
                    <div class="event-hero__image">
                        <p class="variable-color text-right event-hero__caption">Wilco performing at Eventim Apollo on 20 August 2026</p>
                    </div>
                </div>
            </section>
            <div class="site-footer bg-eventim-navy"><p>&copy; 2026 Eventim Apollo. All rights reserved.</p></div>
        """.trimIndent()

        val pageText = EventimApolloGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("A Tour With Wilco is coming to Eventim Apollo this August.")).isTrue()
        expectThat(pageText.contains("pioneering force in independent music")).isTrue()
        expectThat(pageText.contains("TICKETS ON SALE")).isEqualTo(false)
        expectThat(pageText.contains("Buy tickets")).isEqualTo(false)
        expectThat(pageText.contains("performing at Eventim Apollo on")).isEqualTo(false)
        expectThat(pageText.contains("All rights reserved")).isEqualTo(false)
    }
}
