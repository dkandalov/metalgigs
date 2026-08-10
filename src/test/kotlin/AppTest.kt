import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.NOT_FOUND
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
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

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
    fun `extracts gig events from The Dome whatson page`() {
        val events = DomeLondonGigsSource(cachedClient()).latestGigs()
        events.forEach { println(it) }

        expectThat(events).hasSize(70)

        expectThat(events.first()).isEqualTo(
            GigEvent(
                title = "BATTLESNAKE",
                venue = "The Dome",
                year = 2026,
                month = "Aug",
                day = "08",
                url = "https://www.domelondon.co.uk/whatson/08/08-battlesnake",
                imageUrl = "https://images.squarespace-cdn.com/content/v1/6708f569091ee6412723acb9/1777381588492-CAQQZA5RRSD026668882/Cathedral%2BColour.jpg",
            ),
        )
        expectThat(events.last()).isEqualTo(
            GigEvent(
                title = "DRACONIAN",
                venue = "The Dome",
                year = 2027,
                month = "Mar",
                day = "07",
                url = "https://www.domelondon.co.uk/whatson/03/07-draconian",
                imageUrl = "https://images.squarespace-cdn.com/content/v1/6708f569091ee6412723acb9/1771509016965-K3W9K2G4J853EZ97RETL/Draconian+done-56+%28low+res%29.jpg",
            ),
        )

        expectThat(events.all { it.url.startsWith("https://www.domelondon.co.uk/whatson/") }).isTrue()
        expectThat(events.all { it.venue == "The Dome" }).isTrue()
    }

    @Test
    fun `appends and reads back gig log entries of different kinds`() {
        val gig = GigEvent(title = "Test Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/test-gig", imageUrl = "https://example.com/images/test-gig.jpg")
        val scrapedAt = Instant.parse("2026-08-01T12:00:00Z")
        val entries: List<GigLogEntry> = listOf(
            GigObserved(gig, scrapedAt),
            GigClassified(venue = gig.venue, url = gig.url, scrapedAt = scrapedAt, genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
        )
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }

        appendGigLogEntries(file, entries)

        expectThat(readGigLogEntries(file)).isEqualTo(entries)
    }

    @Test
    fun `projects the latest observation per gig, ignoring classification entries`() {
        val firstSeen = GigEvent(title = "Some Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/some-gig", imageUrl = "https://example.com/images/some-gig.jpg")
        val soldOut = firstSeen.copy(title = "Some Gig - SOLD OUT")
        val events: List<GigLogEntry> = listOf(
            GigObserved(firstSeen, Instant.parse("2026-07-01T00:00:00Z")),
            GigClassified(venue = firstSeen.venue, url = firstSeen.url, scrapedAt = Instant.parse("2026-07-10T00:00:00Z"), genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
            GigObserved(soldOut, Instant.parse("2026-07-15T00:00:00Z")),
        )

        expectThat(projectCurrentGigs(events)).isEqualTo(listOf(soldOut))
    }

    @Test
    fun `keeps separate gigs from different venues distinct`() {
        val gigA = GigEvent(title = "Gig A", venue = "Venue A", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/same-slug", imageUrl = "https://example.com/images/gig-a.jpg")
        val gigB = GigEvent(title = "Gig B", venue = "Venue B", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/same-slug", imageUrl = "https://example.com/images/gig-b.jpg")
        val scrapedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events = listOf(GigObserved(gigA, scrapedAt), GigObserved(gigB, scrapedAt))

        expectThat(projectCurrentGigs(events)).containsExactlyInAnyOrder(gigA, gigB)
    }

    @Test
    fun `projects unclassified gigs, including ones never classified at all`() {
        val neverClassified = GigEvent(title = "Never Classified", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/never-classified", imageUrl = "")
        val classifiedMetal = GigEvent(title = "Classified Metal", venue = "Test Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/gigs/classified-metal", imageUrl = "")
        val classifiedUnmatched = GigEvent(title = "Classified Unmatched", venue = "Test Venue", year = 2026, month = "Aug", day = "10", url = "https://example.com/gigs/classified-unmatched", imageUrl = "")
        val scrapedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<GigLogEntry> = listOf(
            GigObserved(neverClassified, scrapedAt),
            GigObserved(classifiedMetal, scrapedAt),
            GigClassified(classifiedMetal.venue, classifiedMetal.url, scrapedAt, genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
            GigObserved(classifiedUnmatched, scrapedAt),
            GigClassified(classifiedUnmatched.venue, classifiedUnmatched.url, scrapedAt, genre = Genre.Unclassified, source = ClassificationSource.Keywords),
        )

        expectThat(projectUnclassifiedGigs(events)).containsExactlyInAnyOrder(neverClassified, classifiedUnmatched)
    }

    @Test
    fun `projects unclassified gigs using only the latest classification per gig`() {
        val gig = GigEvent(title = "Reclassified Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/reclassified", imageUrl = "")
        val events: List<GigLogEntry> = listOf(
            GigObserved(gig, Instant.parse("2026-07-01T00:00:00Z")),
            GigClassified(gig.venue, gig.url, Instant.parse("2026-07-01T00:00:00Z"), genre = Genre.Unclassified, source = ClassificationSource.Keywords),
            GigClassified(gig.venue, gig.url, Instant.parse("2026-07-15T00:00:00Z"), genre = Genre.Metal, matchedKeywords = listOf("thrash"), source = ClassificationSource.Keywords),
        )

        expectThat(projectUnclassifiedGigs(events)).isEqualTo(emptyList())
    }

    @Test
    fun `a later user override takes precedence over an earlier keyword classification`() {
        val gig = GigEvent(title = "Overridden Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/overridden", imageUrl = "")
        val events: List<GigLogEntry> = listOf(
            GigObserved(gig, Instant.parse("2026-07-01T00:00:00Z")),
            GigClassified(gig.venue, gig.url, Instant.parse("2026-07-01T00:00:00Z"), genre = Genre.Unclassified, source = ClassificationSource.Keywords),
            GigClassified(gig.venue, gig.url, Instant.parse("2026-07-15T00:00:00Z"), genre = Genre.Metal, source = ClassificationSource.User),
        )

        expectThat(projectUnclassifiedGigs(events)).isEqualTo(emptyList())
    }

    @Test
    fun `classifies gigs by scanning their event pages, skipping already classified ones`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { request ->
            requestCount++
            val body = if (request.uri.toString().endsWith("metal-gig")) "Doom metal night!" else "Comedy open mic"
            Response(OK).body(body)
        }
        val metalGig = GigEvent(title = "Doom Night", venue = "Some Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/metal-gig", imageUrl = "")
        val comedyGig = GigEvent(title = "Comedy Night", venue = "Some Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/comedy-gig", imageUrl = "")
        val oldGig = GigEvent(title = "Old Gig", venue = "Some Venue", year = 2026, month = "Aug", day = "10", url = "https://example.com/old-gig", imageUrl = "")
        val scrapedAt = Instant.parse("2026-08-01T00:00:00Z")

        val classifications = classifyGigs(
            fakeClient,
            gigs = listOf(metalGig, comedyGig, oldGig),
            alreadyClassified = setOf(oldGig.venue to oldGig.url),
            scrapedAt = scrapedAt,
        )

        expectThat(requestCount).isEqualTo(2)
        expectThat(classifications).containsExactlyInAnyOrder(
            GigClassified(metalGig.venue, metalGig.url, scrapedAt, genre = Genre.Metal, matchedKeywords = listOf("metal", "doom"), source = ClassificationSource.Keywords),
            GigClassified(comedyGig.venue, comedyGig.url, scrapedAt, genre = Genre.Unclassified, source = ClassificationSource.Keywords),
        )
    }

    @Test
    fun `scopes The Underworld classification to the gig's own content, ignoring other-events widgets`() {
        val html = """
            <article class="event">
              <div class="content"><p>Doom metal night!</p></div>
            </article>
            <article class="list">
              <h3 class="list-header-title">KINGS OF THRASH</h3>
            </article>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }
        val gig = GigEvent(title = "Some Gig", venue = "The Underworld", year = 2026, month = "Aug", day = "08", url = "https://example.com/gig", imageUrl = "")

        val classification = classifyGig(fakeClient, gig, Instant.parse("2026-08-01T00:00:00Z"))

        expectThat(classification.matchedKeywords).containsExactly("metal", "doom")
    }

    @Test
    fun `fails fast when a venue's event page content can't be extracted`() {
        val fakeClient: HttpHandler = { Response(OK).body("<div>page markup changed, no article.event here</div>") }
        val gig = GigEvent(title = "Some Gig", venue = "The Underworld", year = 2026, month = "Aug", day = "08", url = "https://example.com/gig", imageUrl = "")

        val error = assertFailsWith<IllegalStateException> { classifyGig(fakeClient, gig, Instant.parse("2026-08-01T00:00:00Z")) }

        expectThat(error.message!!.contains("The Underworld")).isTrue()
        expectThat(error.message!!.contains("https://example.com/gig")).isTrue()
    }

    @Test
    fun `scopes New Cross Inn classification to the client-rendered description attribute`() {
        val html = """
            <p x-ref="desc" x-html="'Doom metal night with support'"></p>
            <div>KINGS OF THRASH</div>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }
        val gig = GigEvent(title = "Some Gig", venue = "New Cross Inn", year = 2026, month = "Aug", day = "08", url = "https://example.com/gig", imageUrl = "")

        val classification = classifyGig(fakeClient, gig, Instant.parse("2026-08-01T00:00:00Z"))

        expectThat(classification.matchedKeywords).containsExactly("metal", "doom")
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

    @Test
    fun `caches downloaded images and skips re-downloading on cache hit`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("fake-image-bytes") }
        val cacheDir = File.createTempFile("images", "").apply { delete(); deleteOnExit() }
        val gig = GigEvent(
            title = "Some Gig",
            venue = "Some Venue",
            year = 2026,
            month = "Aug",
            day = "08",
            url = "https://example.com/gigs/some-gig",
            imageUrl = "https://example.com/images/some-gig.jpg?w=200",
        )

        val first = cacheImage(fakeClient, gig, cacheDir)
        val second = cacheImage(fakeClient, gig, cacheDir)

        expectThat(requestCount).isEqualTo(1)
        expectThat(first).isEqualTo(second)
        expectThat(first.readText()).isEqualTo("fake-image-bytes")
        expectThat(first.name).isEqualTo("2026-08-08-some-venue-1af7931d.jpg")
        expectThat(first.extension).isEqualTo("jpg")
    }

    @Test
    fun `fails fast with gig identity when image download fails`() {
        val fakeClient: HttpHandler = { Response(NOT_FOUND) }
        val cacheDir = File.createTempFile("images", "").apply { delete(); deleteOnExit() }
        val gig = GigEvent(
            title = "Broken Image Gig",
            venue = "Some Venue",
            year = 2026,
            month = "Aug",
            day = "08",
            url = "https://example.com/gigs/broken",
            imageUrl = "https://example.com/images/broken.jpg",
        )

        val error = assertFailsWith<IllegalStateException> { cacheImage(fakeClient, gig, cacheDir) }

        expectThat(error.message!!.contains("Broken Image Gig")).isTrue()
        expectThat(error.message!!.contains("Some Venue")).isTrue()
        expectThat(error.message!!.contains("https://example.com/images/broken.jpg")).isTrue()
    }
}
