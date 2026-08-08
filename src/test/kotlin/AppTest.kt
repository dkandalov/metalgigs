import org.http4k.client.OkHttp
import org.http4k.core.then
import org.http4k.filter.TrafficFilters
import org.http4k.traffic.ReadWriteCache
import org.jsoup.Jsoup
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isNotBlank
import strikt.assertions.isTrue
import java.io.File
import kotlin.test.Test

data class GigEvent(val title: String, val year: Int, val month: String, val day: String, val url: String)

fun parseGigEvents(html: String, baseUri: String, year: Int): List<GigEvent> =
    Jsoup.parse(html, baseUri)
        .select(".news-carousel__item")
        .filter { it.select(".news-carousel__date-wrap").isNotEmpty() }
        .map { item ->
            GigEvent(
                title = item.select(".news-carousel__link").text(),
                year = year,
                month = item.select(".news-carousel__month").text(),
                day = item.select(".news-carousel__day").text(),
                url = item.select(".news-carousel__link").attr("abs:href"),
            )
        }

class AppTest {
    private val fixtures = File("src/test/resources/traffic")

    private fun cachedClient() = TrafficFilters.ServeCachedFrom(ReadWriteCache.Disk(fixtures.absolutePath))
        .then(TrafficFilters.RecordTo(ReadWriteCache.Disk(fixtures.absolutePath)))
        .then(OkHttp())

    @Test
    fun `fetches news page (record on miss, replay on hit)`() {
        val body = fetchPage(cachedClient(), newsUrl)

        expectThat(body).isNotBlank()
        expectThat(body.contains("<html", ignoreCase = true)).isTrue()
    }

    @Test
    fun `extracts gig events from news page`() {
        val body = fetchPage(cachedClient(), newsUrl)

        val events = parseGigEvents(body, newsUrl, year = 2026)
        events.forEach { println(it) }

        expectThat(events).hasSize(21)

        expectThat(events.first()).isEqualTo(
            GigEvent(
                title = "THREE BIRDS WHISPER - The Positive Rebellion Tour UK 2026 + PSYCHEDELIC SKIES + BORDERLINE",
                year = 2026,
                month = "Aug",
                day = "08",
                url = "https://www.cartandhorses.london/news-offers-events/523846-three-birds-whisper-the-positive-rebellion-tour-uk-2026-psychedelic-skies-borderline/",
            ),
        )
        expectThat(events.last()).isEqualTo(
            GigEvent(
                title = "Jbm presents SMELLS LIKE NIRVANA",
                year = 2026,
                month = "Oct",
                day = "10",
                url = "https://www.cartandhorses.london/news-offers-events/517524-jbm-presents-smells-like-nirvana/",
            ),
        )

        expectThat(events.take(3).map { it.month }).containsExactly("Aug", "Aug", "Aug")
        expectThat(events.take(3).map { it.day }).containsExactly("08", "14", "15")

        val titles = events.map { it.title }
        listOf("RHABSTALLION", "HELLBENT FOREVER", "DEAD WITCHES", "POSTMORTEM", "LESBIAN BED DEATH")
            .forEach { band -> expectThat(titles.any { it.contains(band) }).isTrue() }

        expectThat(events.all { it.url.startsWith("https://www.cartandhorses.london/") }).isTrue()
    }
}
