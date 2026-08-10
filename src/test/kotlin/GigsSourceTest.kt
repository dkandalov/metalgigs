import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test

class GigsSourceTest {

    private fun assertScrapesGigs(source: GigsSource, size: Int, first: GigEvent, last: GigEvent, urlPrefix: String): List<GigEvent> {
        val events = source.latestGigs()
        events.forEach { println(it) }

        expectThat(events).hasSize(size)
        expectThat(events.first()).isEqualTo(first)
        expectThat(events.last()).isEqualTo(last)
        expectThat(events.all { it.url.startsWith(urlPrefix) }).isTrue()
        expectThat(events.all { it.venue == first.venue }).isTrue()

        return events
    }

    @Test
    fun `extracts gig events from news page`() {
        val events = assertScrapesGigs(
            source = CartAndHorsesGigsSource(cachedClient(), year = 2026),
            size = 21,
            first = GigEvent(
                title = "THREE BIRDS WHISPER - The Positive Rebellion Tour UK 2026 + PSYCHEDELIC SKIES + BORDERLINE",
                venue = "Cart & Horses",
                year = 2026,
                month = "Aug",
                day = "08",
                url = "https://www.cartandhorses.london/news-offers-events/523846-three-birds-whisper-the-positive-rebellion-tour-uk-2026-psychedelic-skies-borderline/",
                imageUrl = "https://www.useyourlocal.com/imgs/pub_events/sr@1x/240726-012017_threebirds-upd.jpg",
            ),
            last = GigEvent(
                title = "Jbm presents SMELLS LIKE NIRVANA",
                venue = "Cart & Horses",
                year = 2026,
                month = "Oct",
                day = "10",
                url = "https://www.cartandhorses.london/news-offers-events/517524-jbm-presents-smells-like-nirvana/",
                imageUrl = "https://www.useyourlocal.com/imgs/pub_events/sr@1x/270126-043912_smelllike.jpg",
            ),
            urlPrefix = "https://www.cartandhorses.london/",
        )

        expectThat(events.take(3).map { it.month }).containsExactly("Aug", "Aug", "Aug")
        expectThat(events.take(3).map { it.day }).containsExactly("08", "14", "15")

        val titles = events.map { it.title }
        listOf("RHABSTALLION", "HELLBENT FOREVER", "DEAD WITCHES", "POSTMORTEM", "LESBIAN BED DEATH")
            .forEach { band -> expectThat(titles.any { it.contains(band) }).isTrue() }
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
        assertScrapesGigs(
            source = NewCrossInnGigsSource(cachedClient()),
            size = 28,
            first = GigEvent(
                title = "GREENHAT",
                venue = "New Cross Inn",
                year = 2026,
                month = "Aug",
                day = "08",
                url = "https://pit.live/events/greenhat",
                imageUrl = "https://pit.live/uploads/user/2026/07/07/640x480/5d05ygXA94bMG95I.jpg",
            ),
            last = GigEvent(
                title = "Rudies Resurrection",
                venue = "New Cross Inn",
                year = 2026,
                month = "Sep",
                day = "05",
                url = "https://pit.live/events/rudies-resurrection",
                imageUrl = "https://pit.live/uploads/user/2026/07/29/640x480/P8wpWnfgGUUPDWcA.jpg",
            ),
            urlPrefix = "https://pit.live/events/",
        )
    }

    @Test
    fun `extracts gig events from Our Black Heart events page`() {
        assertScrapesGigs(
            source = OurBlackHeartGigsSource(cachedClient()),
            size = 50,
            first = GigEvent(
                title = "YOU WIN AGAIN GRAVITY",
                venue = "Our Black Heart",
                year = 2026,
                month = "Aug",
                day = "08",
                url = "https://www.ourblackheart.com/events/2026/8/8/you-win-again-gravity",
                imageUrl = "https://images.squarespace-cdn.com/content/v1/5486e6cde4b0d80114155bf4/1782745761879-UVSUIG341XJIY3MEB9MI/LBPHOTO%2B-%2B%2BYou%2BWin%2BAgain%2BGravity%2B-%2BPromo%2B-%2B20.10.2024%2B6.jpg",
            ),
            last = GigEvent(
                title = "NECROPOLIS VOL. III",
                venue = "Our Black Heart",
                year = 2027,
                month = "Mar",
                day = "19",
                url = "https://www.ourblackheart.com/events/2027/3/19/necropolis-vol-iii",
                imageUrl = "https://images.squarespace-cdn.com/content/v1/5486e6cde4b0d80114155bf4/1781025655512-MHR6PMWPOOE3TJFOSWAB/Necropolis_2027_IG_Feed_Poster_2nd_announcement%2B%25281%2529.jpg",
            ),
            urlPrefix = "https://www.ourblackheart.com/events/",
        )
    }

    @Test
    fun `extracts gig events from The Underworld search-events page`() {
        assertScrapesGigs(
            source = TheUnderworldGigsSource(cachedClient()),
            size = 74,
            first = GigEvent(
                title = "THE PARTISANS",
                venue = "The Underworld",
                year = 2026,
                month = "Aug",
                day = "08",
                url = "https://www.theunderworldcamden.co.uk/event/the-partisans-8th-aug-the-underworld-london-tickets/",
                imageUrl = "https://dice-media.imgix.net/attachments/2026-04-15/644411f7-5f86-484c-b29b-b71dc309b89e.jpg?rect=734%2C0%2C2682%2C2682&w=200",
            ),
            last = GigEvent(
                title = "ALIVE, A TRIBUTE TO PEARL JAM",
                venue = "The Underworld",
                year = 2027,
                month = "Dec",
                day = "04",
                url = "https://www.theunderworldcamden.co.uk/event/alive-a-tribute-to-pearl-jam-20th-nov-the-underworld-london-tickets/",
                imageUrl = "https://dice-media.imgix.net/attachments/2026-02-10/cf613856-3e58-41a8-b0f0-af044c77c97b.jpg?rect=228%2C0%2C2045%2C2045&w=200",
            ),
            urlPrefix = "https://www.theunderworldcamden.co.uk/event/",
        )
    }

    @Test
    fun `extracts gig events from The Dome whatson page`() {
        assertScrapesGigs(
            source = DomeLondonGigsSource(cachedClient()),
            size = 70,
            first = GigEvent(
                title = "BATTLESNAKE",
                venue = "The Dome",
                year = 2026,
                month = "Aug",
                day = "08",
                url = "https://www.domelondon.co.uk/whatson/08/08-battlesnake",
                imageUrl = "https://images.squarespace-cdn.com/content/v1/6708f569091ee6412723acb9/1777381588492-CAQQZA5RRSD026668882/Cathedral%2BColour.jpg",
            ),
            last = GigEvent(
                title = "DRACONIAN",
                venue = "The Dome",
                year = 2027,
                month = "Mar",
                day = "07",
                url = "https://www.domelondon.co.uk/whatson/03/07-draconian",
                imageUrl = "https://images.squarespace-cdn.com/content/v1/6708f569091ee6412723acb9/1771509016965-K3W9K2G4J853EZ97RETL/Draconian+done-56+%28low+res%29.jpg",
            ),
            urlPrefix = "https://www.domelondon.co.uk/whatson/",
        )
    }

    @Test
    fun `extracts gig events from Blondies Brewery Taproom's dice_fm venue page`() {
        assertScrapesGigs(
            source = BlondiesBreweryTaproomGigsSource(cachedClient()),
            size = 9,
            first = GigEvent(
                title = "It's Never Over, Jeff Buckley > Screening",
                venue = "Blondies Brewery Taproom",
                year = 2026,
                month = "Aug",
                day = "12",
                url = "https://dice.fm/event/2wqb7p-its-never-over-jeff-buckley-screening-12th-aug-blondies-brewery-london-tickets",
                imageUrl = "https://dice-media.imgix.net/attachments/2026-08-03/6088fc1d-076f-4946-b1d6-342519c36355.jpg?rect=0%2C49%2C2159%2C2159",
            ),
            last = GigEvent(
                title = "FORLORN / BIRDWITCH",
                venue = "Blondies Brewery Taproom",
                year = 2026,
                month = "Nov",
                day = "27",
                url = "https://dice.fm/event/8eq9dw-forlorn-birdwitch-27th-nov-blondies-brewery-london-tickets",
                imageUrl = "https://dice-media.imgix.net/attachments/2026-07-13/d2e1f34c-9f57-4a47-811c-5e6d4efbc40a.jpg?rect=0%2C135%2C1080%2C1080",
            ),
            urlPrefix = "https://dice.fm/event/",
        )
    }

    @Test
    fun `extracts gig events from Blondies Bar's dice_fm venue page`() {
        assertScrapesGigs(
            source = BlondiesBarGigsSource(cachedClient()),
            size = 26,
            first = GigEvent(
                title = "Midweek Mayhem – £4 Pints All Night",
                venue = "Blondies Bar",
                year = 2026,
                month = "Aug",
                day = "12",
                url = "https://dice.fm/event/av57g7-midweek-mayhem-4-pints-all-night-12th-aug-blondies-london-tickets",
                imageUrl = "https://dice-media.imgix.net/attachments/2025-07-23/03c4258d-44cc-4c61-8612-5d5495f6684b.jpg?rect=0%2C0%2C4385%2C4385",
            ),
            last = GigEvent(
                title = "1986 + Support",
                venue = "Blondies Bar",
                year = 2026,
                month = "Dec",
                day = "05",
                url = "https://dice.fm/event/bboxdm-1986-support-5th-dec-blondies-london-tickets",
                imageUrl = "https://dice-media.imgix.net/attachments/2026-04-27/4c005268-bc5b-43c7-a69f-8117623d0232.jpg?rect=0%2C0%2C2048%2C2048",
            ),
            urlPrefix = "https://dice.fm/event/",
        )
    }

    @Test
    fun `extracts gig events from Helgi's dice_fm venue page`() {
        assertScrapesGigs(
            source = HelgisGigsSource(cachedClient()),
            size = 15,
            first = GigEvent(
                title = "Sceptocrypt + In Gods Way + Cariad",
                venue = "Helgi's",
                year = 2026,
                month = "Aug",
                day = "14",
                url = "https://dice.fm/event/avrpa2-sceptocrypt-in-gods-way-cariad-14th-aug-helgis-london-tickets",
                imageUrl = "https://dice-media.imgix.net/attachments/2026-08-09/bcabb7e3-0777-4c15-929c-9192d05503fb.jpg?rect=0%2C32%2C1187%2C1187",
            ),
            last = GigEvent(
                title = "HOLOCAUST + HYENA",
                venue = "Helgi's",
                year = 2026,
                month = "Nov",
                day = "14",
                url = "https://dice.fm/event/xedvra-holocaust-hyena-14th-nov-helgis-london-tickets",
                imageUrl = "https://dice-media.imgix.net/attachments/2026-04-07/cdca232f-2df2-41a6-a2b1-cdaa5c827aa3.jpg?rect=0%2C135%2C1080%2C1080",
            ),
            urlPrefix = "https://dice.fm/event/",
        )
    }

    @Test
    fun `extracts gig events from Electric Ballroom whats-on page`() {
        assertScrapesGigs(
            source = ElectricBallroomGigsSource(cachedClient(), year = 2026),
            size = 89,
            first = GigEvent(
                title = "Lion Babe – RESCHEDULED!",
                venue = "Electric Ballroom",
                year = 2026,
                month = "Aug",
                day = "13",
                url = "https://electricballroom.co.uk/lion-babe/",
                imageUrl = "https://electricballroom.co.uk/wp-content/uploads/2026/07/LION-BABE-.jpg",
            ),
            last = GigEvent(
                title = "Indiepalooza Tribute – Killers v Monkeys v Fender v Oasis v Kasabian v Kaiser",
                venue = "Electric Ballroom",
                year = 2027,
                month = "Jun",
                day = "19",
                url = "https://electricballroom.co.uk/indiepalooza-tribute-killers-v-monkeys-v-fender-v-oasis-v-kasabian-v-kaiser/",
                imageUrl = "https://electricballroom.co.uk/wp-content/uploads/2026/06/Indiepalooza-2027.jpg",
            ),
            urlPrefix = "https://electricballroom.co.uk/",
        )
    }
}
