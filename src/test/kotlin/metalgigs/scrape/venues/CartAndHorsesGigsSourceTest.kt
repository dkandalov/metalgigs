package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test

// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
// Why the date is read this way: docs/adr/0010-a-date-is-read-per-venue-and-a-missing-year-is-inferred.md
class CartAndHorsesGigsSourceTest {

    @Test
    fun `extracts gig events from news page`() {
        val events = assertScrapesGigs(
            source = CartAndHorsesGigsSource(cachedClient(), year = 2026),
            size = 21,
            first = Gig(
                GigId(cartAndHorses.id, GigUrl("https://www.cartandhorses.london/news-offers-events/523846-three-birds-whisper-the-positive-rebellion-tour-uk-2026-psychedelic-skies-borderline/")),
                GigTitle("THREE BIRDS WHISPER - The Positive Rebellion Tour UK 2026 + PSYCHEDELIC SKIES + BORDERLINE"),
                GigDate(2026, 8, 8),
                PosterUrl("https://www.useyourlocal.com/imgs/pub_events/sr@1x/240726-012017_threebirds-upd.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(cartAndHorses.id, GigUrl("https://www.cartandhorses.london/news-offers-events/517524-jbm-presents-smells-like-nirvana/")),
                GigTitle("Jbm presents SMELLS LIKE NIRVANA"),
                GigDate(2026, 10, 10),
                PosterUrl("https://www.useyourlocal.com/imgs/pub_events/sr@1x/270126-043912_smelllike.jpg"),
                GigDescription(""),
            ),
        )

        expectThat(events.take(3).map { it.date })
            .containsExactly(GigDate(2026, 8, 8), GigDate(2026, 8, 14), GigDate(2026, 8, 15))

        val titles = events.map { it.title.value }
        listOf("RHABSTALLION", "HELLBENT FOREVER", "DEAD WITCHES", "POSTMORTEM", "LESBIAN BED DEATH")
            .forEach { band -> expectThat(titles.any { it.contains(band) }).isTrue() }
    }

    @Test
    fun `rolls over the year when Cart and Horses gigs cross into January`() {
        val html = """
            <div class="news-carousel__item">
                <img class="news-carousel__image" src="https://example.com/poster.jpg">
                <a class="news-carousel__link" href="/news-offers-events/1-dec-gig/">DEC GIG</a>
                <div class="news-carousel__date-wrap">
                    <div class="news-carousel__month">Dec</div>
                    <div class="news-carousel__day">20</div>
                </div>
            </div>
            <div class="news-carousel__item">
                <img class="news-carousel__image" src="https://example.com/poster.jpg">
                <a class="news-carousel__link" href="/news-offers-events/2-jan-gig/">JAN GIG</a>
                <div class="news-carousel__date-wrap">
                    <div class="news-carousel__month">Jan</div>
                    <div class="news-carousel__day">10</div>
                </div>
            </div>
            <div class="news-carousel__item">
                <img class="news-carousel__image" src="https://example.com/poster.jpg">
                <a class="news-carousel__link" href="/news-offers-events/3-feb-gig/">FEB GIG</a>
                <div class="news-carousel__date-wrap">
                    <div class="news-carousel__month">Feb</div>
                    <div class="news-carousel__day">01</div>
                </div>
            </div>
            <!-- the same body answers this source's event-page requests, which now have to yield a
                 description rather than being allowed to come back empty -->
            <div class="page_content_inner">Doom night, doors 7pm.</div>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }

        val events = CartAndHorsesGigsSource(fakeClient, year = 2026).latestGigs()

        expectThat(events.map { it.date.year }).containsExactly(2026, 2027, 2027)
    }

    @Test
    fun `scopes Cart & Horses page text to the page header and content, ignoring nav and footer`() {
        val html = """
            <nav><a>Sign up</a><a>Food & Drink</a></nav>
            <header class="page_header"><h1>Doom Night</h1></header>
            <div class="page_content_inner"><p>Doom metal night!</p></div>
            <footer>Opening times Mon: 12:00 - 00:00 Cart & Horses 1 Maryland Point</footer>
        """.trimIndent()

        val pageText = CartAndHorsesGigsSource(noHttp, year = 2026).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom Night")).isTrue()
        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Food & Drink")).isEqualTo(false)
        expectThat(pageText.contains("Opening times")).isEqualTo(false)
    }
}
