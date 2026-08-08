import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.then
import org.http4k.filter.TrafficFilters
import org.http4k.traffic.ReadWriteCache
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.io.File
import kotlin.test.Test

class AppTest {
    private val fixtures = File("src/test/resources/traffic")

    private fun cachedClient(): HttpHandler = TrafficFilters.ServeCachedFrom(ReadWriteCache.Disk(fixtures.absolutePath))
        .then(TrafficFilters.RecordTo(ReadWriteCache.Disk(fixtures.absolutePath)))
        .then(OkHttp())

    @Test
    fun `extracts gig events from news page`() {
        val events = CartAndHorsesGigsSource(cachedClient(), year = 2026).latestGigs()
        events.forEach { println(it) }

        expectThat(events).hasSize(21)

        expectThat(events.first()).isEqualTo(
            GigEvent(
                title = "THREE BIRDS WHISPER - The Positive Rebellion Tour UK 2026 + PSYCHEDELIC SKIES + BORDERLINE",
                venue = "Cart & Horses",
                year = 2026,
                month = "Aug",
                day = "08",
                url = "https://www.cartandhorses.london/news-offers-events/523846-three-birds-whisper-the-positive-rebellion-tour-uk-2026-psychedelic-skies-borderline/",
            ),
        )
        expectThat(events.last()).isEqualTo(
            GigEvent(
                title = "Jbm presents SMELLS LIKE NIRVANA",
                venue = "Cart & Horses",
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
        expectThat(events.all { it.venue == "Cart & Horses" }).isTrue()
    }

    @Test
    fun `extracts gig events from New Cross Inn gigs page`() {
        val events = NewCrossInnGigsSource(cachedClient()).latestGigs()
        events.forEach { println(it) }

        expectThat(events).hasSize(28)

        expectThat(events.first()).isEqualTo(
            GigEvent(
                title = "GREENHAT",
                venue = "New Cross Inn",
                year = 2026,
                month = "Aug",
                day = "08",
                url = "https://pit.live/events/greenhat",
            ),
        )
        expectThat(events.last()).isEqualTo(
            GigEvent(
                title = "Rudies Resurrection",
                venue = "New Cross Inn",
                year = 2026,
                month = "Sep",
                day = "05",
                url = "https://pit.live/events/rudies-resurrection",
            ),
        )

        expectThat(events.all { it.url.startsWith("https://pit.live/events/") }).isTrue()
        expectThat(events.all { it.venue == "New Cross Inn" }).isTrue()
    }

    @Test
    fun `extracts gig events from Our Black Heart events page`() {
        val events = OurBlackHeartGigsSource(cachedClient()).latestGigs()
        events.forEach { println(it) }

        expectThat(events).hasSize(50)

        expectThat(events.first()).isEqualTo(
            GigEvent(
                title = "YOU WIN AGAIN GRAVITY",
                venue = "Our Black Heart",
                year = 2026,
                month = "Aug",
                day = "08",
                url = "https://www.ourblackheart.com/events/2026/8/8/you-win-again-gravity",
            ),
        )
        expectThat(events.last()).isEqualTo(
            GigEvent(
                title = "NECROPOLIS VOL. III",
                venue = "Our Black Heart",
                year = 2027,
                month = "Mar",
                day = "19",
                url = "https://www.ourblackheart.com/events/2027/3/19/necropolis-vol-iii",
            ),
        )

        expectThat(events.all { it.url.startsWith("https://www.ourblackheart.com/events/") }).isTrue()
        expectThat(events.all { it.venue == "Our Black Heart" }).isTrue()
    }

    @Test
    fun `persists gigs as ndjson`() {
        val gigs = listOf(
            GigEvent(title = "Test Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/test-gig"),
            GigEvent(title = "Another Gig", venue = "Test Venue", year = 2026, month = "Sep", day = "01", url = "https://example.com/gigs/another-gig"),
        )
        val file = File.createTempFile("gigs", ".ndjson").apply { deleteOnExit() }

        writeGigsNdJson(file, gigs)

        expectThat(readGigsNdJson(file)).isEqualTo(gigs)
    }
}
