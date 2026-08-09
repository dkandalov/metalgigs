import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.core.then
import org.http4k.filter.TrafficFilters
import org.http4k.template.HandlebarsTemplates
import org.http4k.testing.ApprovalTest
import org.http4k.testing.Approver
import org.http4k.traffic.ReadWriteCache
import org.junit.jupiter.api.extension.ExtendWith
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.io.File
import kotlin.test.Test

@ExtendWith(ApprovalTest::class)
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
                imageUrl = "https://www.useyourlocal.com/imgs/pub_events/sr@1x/240726-012017_threebirds-upd.jpg",
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
                imageUrl = "https://www.useyourlocal.com/imgs/pub_events/sr@1x/270126-043912_smelllike.jpg",
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
    fun `rolls over the year when Cart and Horses gigs cross into January`() {
        val html = """
            <div class="news-carousel__item">
                <a class="news-carousel__link" href="/news-offers-events/1-dec-gig/">DEC GIG</a>
                <div class="news-carousel__date-wrap">
                    <div class="news-carousel__month">Dec</div>
                    <div class="news-carousel__day">20</div>
                </div>
            </div>
            <div class="news-carousel__item">
                <a class="news-carousel__link" href="/news-offers-events/2-jan-gig/">JAN GIG</a>
                <div class="news-carousel__date-wrap">
                    <div class="news-carousel__month">Jan</div>
                    <div class="news-carousel__day">10</div>
                </div>
            </div>
            <div class="news-carousel__item">
                <a class="news-carousel__link" href="/news-offers-events/3-feb-gig/">FEB GIG</a>
                <div class="news-carousel__date-wrap">
                    <div class="news-carousel__month">Feb</div>
                    <div class="news-carousel__day">01</div>
                </div>
            </div>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }

        val events = CartAndHorsesGigsSource(fakeClient, year = 2026).latestGigs()

        expectThat(events.map { it.year }).containsExactly(2026, 2027, 2027)
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
                imageUrl = "https://pit.live/uploads/user/2026/07/07/640x480/5d05ygXA94bMG95I.jpg",
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
                imageUrl = "https://pit.live/uploads/user/2026/07/29/640x480/P8wpWnfgGUUPDWcA.jpg",
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
                imageUrl = "https://images.squarespace-cdn.com/content/v1/5486e6cde4b0d80114155bf4/1782745761879-UVSUIG341XJIY3MEB9MI/LBPHOTO%2B-%2B%2BYou%2BWin%2BAgain%2BGravity%2B-%2BPromo%2B-%2B20.10.2024%2B6.jpg",
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
                imageUrl = "https://images.squarespace-cdn.com/content/v1/5486e6cde4b0d80114155bf4/1781025655512-MHR6PMWPOOE3TJFOSWAB/Necropolis_2027_IG_Feed_Poster_2nd_announcement%2B%25281%2529.jpg",
            ),
        )

        expectThat(events.all { it.url.startsWith("https://www.ourblackheart.com/events/") }).isTrue()
        expectThat(events.all { it.venue == "Our Black Heart" }).isTrue()
    }

    @Test
    fun `extracts gig events from The Underworld search-events page`() {
        val events = TheUnderworldGigsSource(cachedClient()).latestGigs()
        events.forEach { println(it) }

        expectThat(events).hasSize(74)

        expectThat(events.first()).isEqualTo(
            GigEvent(
                title = "THE PARTISANS",
                venue = "The Underworld",
                year = 2026,
                month = "Aug",
                day = "08",
                url = "https://www.theunderworldcamden.co.uk/event/the-partisans-8th-aug-the-underworld-london-tickets/",
                imageUrl = "https://dice-media.imgix.net/attachments/2026-04-15/644411f7-5f86-484c-b29b-b71dc309b89e.jpg?rect=734%2C0%2C2682%2C2682&w=200",
            ),
        )
        expectThat(events.last()).isEqualTo(
            GigEvent(
                title = "ALIVE, A TRIBUTE TO PEARL JAM",
                venue = "The Underworld",
                year = 2027,
                month = "Dec",
                day = "04",
                url = "https://www.theunderworldcamden.co.uk/event/alive-a-tribute-to-pearl-jam-20th-nov-the-underworld-london-tickets/",
                imageUrl = "https://dice-media.imgix.net/attachments/2026-02-10/cf613856-3e58-41a8-b0f0-af044c77c97b.jpg?rect=228%2C0%2C2045%2C2045&w=200",
            ),
        )

        expectThat(events.all { it.url.startsWith("https://www.theunderworldcamden.co.uk/event/") }).isTrue()
        expectThat(events.all { it.venue == "The Underworld" }).isTrue()
    }

    @Test
    fun `persists gigs as ndjson`() {
        val gigs = listOf(
            GigEvent(title = "Test Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/test-gig", imageUrl = "https://example.com/images/test-gig.jpg"),
            GigEvent(title = "Another Gig", venue = "Test Venue", year = 2026, month = "Sep", day = "01", url = "https://example.com/gigs/another-gig", imageUrl = "https://example.com/images/another-gig.jpg"),
        )
        val file = File.createTempFile("gigs", ".ndjson").apply { deleteOnExit() }

        writeGigsNdJson(file, gigs)

        expectThat(readGigsNdJson(file)).isEqualTo(gigs)
    }

    @Test
    fun `renders gigs grouped by date as html`(approver: Approver) {
        val gigs = listOf(
            GigEvent(title = "Late Gig", venue = "Venue A", year = 2026, month = "Sep", day = "01", url = "https://example.com/gigs/late-gig", imageUrl = "https://example.com/images/late-gig.jpg"),
            GigEvent(title = "Early Gig One", venue = "Venue A", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/early-gig-one", imageUrl = "https://example.com/images/early-gig-one.jpg"),
            GigEvent(title = "Early Gig Two", venue = "Venue B", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/early-gig-two", imageUrl = "https://example.com/images/early-gig-two.jpg"),
        )
        val renderer = HandlebarsTemplates().CachingClasspath()

        val html = renderer(GigsView(groupGigsByDate(gigs)))

        approver.assertApproved(Response(OK).body(html))
    }
}
