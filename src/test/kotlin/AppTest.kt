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
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
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

private val newCrossInnDatePattern = Regex("""(\d{2}) (\w{3}) (\d{4})""")

fun parseNewCrossInnGigEvents(html: String, baseUri: String): List<GigEvent> =
    Jsoup.parse(html, baseUri)
        .select("li:has(h3.nci-event-name)")
        .map { item ->
            val (day, month, year) = newCrossInnDatePattern.find(item.select("dd").text())!!.destructured
            GigEvent(
                title = item.select("h3.nci-event-name").text(),
                year = year.toInt(),
                month = month,
                day = day,
                url = item.select("a").first()!!.attr("abs:href"),
            )
        }

fun parseOurBlackHeartGigEvents(html: String, baseUri: String): List<GigEvent> =
    Jsoup.parse(html, baseUri)
        .select("article.eventlist-event--upcoming")
        .map { item ->
            val date = LocalDate.parse(item.select("time.event-date").first()!!.attr("datetime"))
            GigEvent(
                title = item.select(".eventlist-title-link").text(),
                year = date.year,
                month = date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                day = "%02d".format(date.dayOfMonth),
                url = item.select(".eventlist-title-link").attr("abs:href"),
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

    @Test
    fun `extracts gig events from New Cross Inn gigs page`() {
        val body = fetchPage(cachedClient(), gigsUrl)

        val events = parseNewCrossInnGigEvents(body, gigsUrl)
        events.forEach { println(it) }

        expectThat(events).hasSize(28)

        expectThat(events.first()).isEqualTo(
            GigEvent(
                title = "GREENHAT",
                year = 2026,
                month = "Aug",
                day = "08",
                url = "https://pit.live/events/greenhat",
            ),
        )
        expectThat(events.last()).isEqualTo(
            GigEvent(
                title = "Rudies Resurrection",
                year = 2026,
                month = "Sep",
                day = "05",
                url = "https://pit.live/events/rudies-resurrection",
            ),
        )

        expectThat(events.all { it.url.startsWith("https://pit.live/events/") }).isTrue()
    }

    @Test
    fun `extracts gig events from Our Black Heart events page`() {
        val body = fetchPage(cachedClient(), ourBlackHeartUrl)

        val events = parseOurBlackHeartGigEvents(body, ourBlackHeartUrl)
        events.forEach { println(it) }

        expectThat(events).hasSize(50)

        expectThat(events.first()).isEqualTo(
            GigEvent(
                title = "YOU WIN AGAIN GRAVITY",
                year = 2026,
                month = "Aug",
                day = "08",
                url = "https://www.ourblackheart.com/events/2026/8/8/you-win-again-gravity",
            ),
        )
        expectThat(events.last()).isEqualTo(
            GigEvent(
                title = "NECROPOLIS VOL. III",
                year = 2027,
                month = "Mar",
                day = "19",
                url = "https://www.ourblackheart.com/events/2027/3/19/necropolis-vol-iii",
            ),
        )

        expectThat(events.all { it.url.startsWith("https://www.ourblackheart.com/events/") }).isTrue()
    }
}
