import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.time.LocalDate
import kotlin.test.Test

class GigsSourceTest {

    private fun assertScrapesGigs(source: GigsSource, size: Int, first: Gig, last: Gig, urlPrefix: String): List<Gig> {
        val events = source.latestGigs()
        events.forEach { println(it) }

        expectThat(events).hasSize(size)
        expectThat(events.first()).isEqualTo(first)
        expectThat(events.last()).isEqualTo(last)
        expectThat(events.all { it.id.url.startsWith(urlPrefix) }).isTrue()
        expectThat(events.all { it.id.venue == first.id.venue }).isTrue()

        return events
    }

    @Test
    fun `extracts gig events from news page`() {
        val events = assertScrapesGigs(
            source = CartAndHorsesGigsSource(cachedClient(), year = 2026),
            size = 21,
            first = Gig(
                id = GigId(cartAndHorses, "https://www.cartandhorses.london/news-offers-events/523846-three-birds-whisper-the-positive-rebellion-tour-uk-2026-psychedelic-skies-borderline/"),
                title = "THREE BIRDS WHISPER - The Positive Rebellion Tour UK 2026 + PSYCHEDELIC SKIES + BORDERLINE",
                date = LocalDate.of(2026, 8, 8),
                imageUrl = "https://www.useyourlocal.com/imgs/pub_events/sr@1x/240726-012017_threebirds-upd.jpg",
            ),
            last = Gig(
                id = GigId(cartAndHorses, "https://www.cartandhorses.london/news-offers-events/517524-jbm-presents-smells-like-nirvana/"),
                title = "Jbm presents SMELLS LIKE NIRVANA",
                date = LocalDate.of(2026, 10, 10),
                imageUrl = "https://www.useyourlocal.com/imgs/pub_events/sr@1x/270126-043912_smelllike.jpg",
            ),
            urlPrefix = "https://www.cartandhorses.london/",
        )

        expectThat(events.take(3).map { it.date })
            .containsExactly(LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 15))

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

        expectThat(events.map { it.date.year }).containsExactly(2026, 2027, 2027)
    }

    @Test
    fun `extracts gig events from New Cross Inn gigs page`() {
        assertScrapesGigs(
            source = NewCrossInnGigsSource(cachedClient()),
            size = 28,
            first = Gig(
                id = GigId(newCrossInn, "https://pit.live/events/greenhat"),
                title = "GREENHAT",
                date = LocalDate.of(2026, 8, 8),
                imageUrl = "https://pit.live/uploads/user/2026/07/07/640x480/5d05ygXA94bMG95I.jpg",
            ),
            last = Gig(
                id = GigId(newCrossInn, "https://pit.live/events/rudies-resurrection"),
                title = "Rudies Resurrection",
                date = LocalDate.of(2026, 9, 5),
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
            first = Gig(
                id = GigId(ourBlackHeart, "https://www.ourblackheart.com/events/2026/8/8/you-win-again-gravity"),
                title = "YOU WIN AGAIN GRAVITY",
                date = LocalDate.of(2026, 8, 8),
                imageUrl = "https://images.squarespace-cdn.com/content/v1/5486e6cde4b0d80114155bf4/1782745761879-UVSUIG341XJIY3MEB9MI/LBPHOTO%2B-%2B%2BYou%2BWin%2BAgain%2BGravity%2B-%2BPromo%2B-%2B20.10.2024%2B6.jpg",
            ),
            last = Gig(
                id = GigId(ourBlackHeart, "https://www.ourblackheart.com/events/2027/3/19/necropolis-vol-iii"),
                title = "NECROPOLIS VOL. III",
                date = LocalDate.of(2027, 3, 19),
                imageUrl = "https://images.squarespace-cdn.com/content/v1/5486e6cde4b0d80114155bf4/1781025655512-MHR6PMWPOOE3TJFOSWAB/Necropolis_2027_IG_Feed_Poster_2nd_announcement%2B%25281%2529.jpg",
            ),
            urlPrefix = "https://www.ourblackheart.com/events/",
        )
    }

    @Test
    fun `extracts gig events from The Underworld search-events page`() {
        val events = assertScrapesGigs(
            source = TheUnderworldGigsSource(cachedClient()),
            size = 74,
            first = Gig(
                id = GigId(theUnderworld, "https://www.theunderworldcamden.co.uk/event/the-partisans-8th-aug-the-underworld-london-tickets/"),
                title = "THE PARTISANS",
                date = LocalDate.of(2026, 8, 8),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-04-15/644411f7-5f86-484c-b29b-b71dc309b89e.jpg?rect=734%2C0%2C2682%2C2682",
            ),
            last = Gig(
                id = GigId(theUnderworld, "https://www.theunderworldcamden.co.uk/event/alive-a-tribute-to-pearl-jam-20th-nov-the-underworld-london-tickets/"),
                title = "ALIVE, A TRIBUTE TO PEARL JAM",
                date = LocalDate.of(2027, 12, 4),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-02-10/cf613856-3e58-41a8-b0f0-af044c77c97b.jpg?rect=228%2C0%2C2045%2C2045",
            ),
            urlPrefix = "https://www.theunderworldcamden.co.uk/event/",
        )

        // the listing asks imgix for w=200 thumbnails; keeping that would publish 200px images for
        // this venue and nothing downstream could recover the detail, so no image url keeps a width
        expectThat(events.count { it.imageUrl.contains("w=") }).isEqualTo(0)
        expectThat(events.count { it.imageUrl.contains("imgix.net") }).isEqualTo(73)
    }

    @Test
    fun `strips only the width, leaving other imgix parameters and non-imgix urls alone`() {
        val rect = "https://dice-media.imgix.net/a.jpg?rect=1%2C0%2C99%2C99"

        expectThat(imgixUrlWithoutWidth("$rect&w=200")).isEqualTo(rect)
        expectThat(imgixUrlWithoutWidth("https://dice-media.imgix.net/a.jpg?w=200&rect=1")).isEqualTo("https://dice-media.imgix.net/a.jpg?rect=1")
        expectThat(imgixUrlWithoutWidth("https://dice-media.imgix.net/a.jpg?w=200")).isEqualTo("https://dice-media.imgix.net/a.jpg")
        expectThat(imgixUrlWithoutWidth(rect)).isEqualTo(rect)
        // a width elsewhere isn't imgix's, so it's left alone rather than guessed at
        expectThat(imgixUrlWithoutWidth("https://example.com/a.jpg?w=200")).isEqualTo("https://example.com/a.jpg?w=200")
    }

    @Test
    fun `extracts gig events from The Dome whatson page`() {
        assertScrapesGigs(
            source = DomeLondonGigsSource(cachedClient()),
            size = 70,
            first = Gig(
                id = GigId(theDome, "https://www.domelondon.co.uk/whatson/08/08-battlesnake"),
                title = "BATTLESNAKE",
                date = LocalDate.of(2026, 8, 8),
                imageUrl = "https://images.squarespace-cdn.com/content/v1/6708f569091ee6412723acb9/1777381588492-CAQQZA5RRSD026668882/Cathedral%2BColour.jpg",
            ),
            last = Gig(
                id = GigId(theDome, "https://www.domelondon.co.uk/whatson/03/07-draconian"),
                title = "DRACONIAN",
                date = LocalDate.of(2027, 3, 7),
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
            first = Gig(
                id = GigId(blondiesBreweryTaproom, "https://dice.fm/event/2wqb7p-its-never-over-jeff-buckley-screening-12th-aug-blondies-brewery-london-tickets"),
                title = "It's Never Over, Jeff Buckley > Screening",
                date = LocalDate.of(2026, 8, 12),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-08-03/6088fc1d-076f-4946-b1d6-342519c36355.jpg?rect=0%2C49%2C2159%2C2159",
            ),
            last = Gig(
                id = GigId(blondiesBreweryTaproom, "https://dice.fm/event/8eq9dw-forlorn-birdwitch-27th-nov-blondies-brewery-london-tickets"),
                title = "FORLORN / BIRDWITCH",
                date = LocalDate.of(2026, 11, 27),
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
            first = Gig(
                id = GigId(blondiesBar, "https://dice.fm/event/av57g7-midweek-mayhem-4-pints-all-night-12th-aug-blondies-london-tickets"),
                title = "Midweek Mayhem – £4 Pints All Night",
                date = LocalDate.of(2026, 8, 12),
                imageUrl = "https://dice-media.imgix.net/attachments/2025-07-23/03c4258d-44cc-4c61-8612-5d5495f6684b.jpg?rect=0%2C0%2C4385%2C4385",
            ),
            last = Gig(
                id = GigId(blondiesBar, "https://dice.fm/event/bboxdm-1986-support-5th-dec-blondies-london-tickets"),
                title = "1986 + Support",
                date = LocalDate.of(2026, 12, 5),
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
            first = Gig(
                id = GigId(helgis, "https://dice.fm/event/avrpa2-sceptocrypt-in-gods-way-cariad-14th-aug-helgis-london-tickets"),
                title = "Sceptocrypt + In Gods Way + Cariad",
                date = LocalDate.of(2026, 8, 14),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-08-09/bcabb7e3-0777-4c15-929c-9192d05503fb.jpg?rect=0%2C32%2C1187%2C1187",
            ),
            last = Gig(
                id = GigId(helgis, "https://dice.fm/event/xedvra-holocaust-hyena-14th-nov-helgis-london-tickets"),
                title = "HOLOCAUST + HYENA",
                date = LocalDate.of(2026, 11, 14),
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
            first = Gig(
                id = GigId(electricBallroom, "https://electricballroom.co.uk/lion-babe/"),
                title = "Lion Babe – RESCHEDULED!",
                date = LocalDate.of(2026, 8, 13),
                imageUrl = "https://electricballroom.co.uk/wp-content/uploads/2026/07/LION-BABE-.jpg",
            ),
            last = Gig(
                id = GigId(electricBallroom, "https://electricballroom.co.uk/indiepalooza-tribute-killers-v-monkeys-v-fender-v-oasis-v-kasabian-v-kaiser/"),
                title = "Indiepalooza Tribute – Killers v Monkeys v Fender v Oasis v Kasabian v Kaiser",
                date = LocalDate.of(2027, 6, 19),
                imageUrl = "https://electricballroom.co.uk/wp-content/uploads/2026/06/Indiepalooza-2027.jpg",
            ),
            urlPrefix = "https://electricballroom.co.uk/",
        )
    }

    @Test
    fun `extracts gig events from Dingwalls whats-on page`() {
        assertScrapesGigs(
            source = DingwallsGigsSource(cachedClient()),
            size = 24,
            first = Gig(
                id = GigId(dingwalls, "https://dingwalls.com/gig/root-company/"),
                title = "BANG YONGGUK",
                date = LocalDate.of(2026, 9, 2),
                imageUrl = "https://dingwalls.com/wp-content/uploads/elementor/thumbs/PP-5-ropdtf0hg2d9yqdycam42ynoc5vdz4n4gsylj8c3l8.png",
            ),
            last = Gig(
                id = GigId(dingwalls, "https://dingwalls.com/gig/rock-for-hope-2/"),
                title = "Rock For Hope",
                date = LocalDate.of(2026, 11, 7),
                imageUrl = "https://dingwalls.com/wp-content/uploads/elementor/thumbs/PP-27-rr5voszodg8dz4qw6s0thhnj6cm8eai4qgy0bw9ru4.jpg",
            ),
            urlPrefix = "https://dingwalls.com/gig/",
        )
    }

    @Test
    fun `extracts gig events from The Garage live page`() {
        val events = assertScrapesGigs(
            source = TheGarageGigsSource(cachedClient()),
            size = 43,
            first = Gig(
                id = GigId(theGarage, "https://www.thegarage.london/gigs/when-chai-met-toast/"),
                title = "WHEN CHAI MET TOAST",
                date = LocalDate.of(2026, 8, 14),
                imageUrl = "",
            ),
            last = Gig(
                id = GigId(theGarage, "https://www.thegarage.london/gigs/black-altar-xxx-anniversary-show-the-garage-london-tickets-2026/"),
                title = "BLACK ALTAR - XXX ANNIVERSARY SHOW",
                date = LocalDate.of(2026, 10, 31),
                imageUrl = "https://www.thegarage.london/wp-content/uploads/2026/07/XXXYears-Poster-4-insta-819x1024.jpg",
            ),
            urlPrefix = "https://www.thegarage.london/gigs/",
        )

        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(1)
    }

    @Test
    fun `extracts gig events from The Grace whats-on page`() {
        assertScrapesGigs(
            source = TheGraceGigsSource(cachedClient()),
            size = 48,
            first = Gig(
                id = GigId(theGrace, "https://www.thegrace.london/gigs/flamebearer-the-grace-london-tickets-2026/"),
                title = "FLAMEBEARER",
                date = LocalDate.of(2026, 8, 14),
                imageUrl = "https://www.thegrace.london/wp-content/uploads/2026/05/FLAMEBEARER_IGNITER_ALBUM_LAUNCH_POSTER_SQUARE_v3_MED_RES_RGB-1-1024x1024.jpg",
            ),
            last = Gig(
                id = GigId(theGrace, "https://www.thegrace.london/gigs/dreamdnvr-the-grace-london-tickets-2026/"),
                title = "DREAMDNVR",
                date = LocalDate.of(2026, 10, 31),
                imageUrl = "https://www.thegrace.london/wp-content/uploads/2026/05/PRESS-PHOTO-DD-3-1-1024x683.jpg",
            ),
            urlPrefix = "https://www.thegrace.london/gigs/",
        )
    }

    @Test
    fun `takes a sold-out DHP gig's url from its notification, since its heading isn't a link`() {
        val html = """
            <div class="card card--full card--contains-notification">
              <mark class="notification card__notification">
                <a href="https://example.com/gigs/sold-out-gig/"><h4 class="notification__title">Gig Sold Out</h4></a>
              </mark>
              <div class="card__strip">
                <h6 class="card__strip-heading">Live</h6>
                <h6 class="card__strip-heading card__strip-heading--last">Sat.03.Oct.26</h6>
              </div>
              <div class="card__grid">
                <div class="card__grid-media media">
                  <img data-lazy-src="https://example.com/poster.jpg" />
                </div>
                <h4 class="card__heading">SOLD OUT GIG</h4>
              </div>
            </div>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }

        val events = DhpVenueGigsSource(fakeClient, url = "https://example.com/whats-on/", venue = Venue("Some Venue")).latestGigs()

        expectThat(events).containsExactly(
            Gig(
                id = GigId(Venue("Some Venue"), "https://example.com/gigs/sold-out-gig/"),
                title = "SOLD OUT GIG",
                date = LocalDate.of(2026, 10, 3),
                imageUrl = "https://example.com/poster.jpg",
            ),
        )
    }

    @Test
    fun `extracts gig events from the Roundhouse whats-on page`() {
        assertScrapesGigs(
            source = RoundhouseGigsSource(cachedClient()),
            size = 9,
            first = Gig(
                id = GigId(roundhouse, "https://www.roundhouse.org.uk/whats-on/c59-theatre-week-15-17-sh26/"),
                title = "Centre 59 Theatre Week (15-17s)",
                date = LocalDate.of(2026, 8, 12),
                imageUrl = "https://assets.roundhouse.org.uk/app/uploads/2026/05/C59-15-17-1260x1280.jpg",
            ),
            last = Gig(
                id = GigId(roundhouse, "https://www.roundhouse.org.uk/whats-on/open-daw-ableton-18-25-sh26/"),
                title = "Open DAW Series: Ableton for Intermediates",
                date = LocalDate.of(2026, 8, 17),
                imageUrl = "https://assets.roundhouse.org.uk/app/uploads/2026/05/Open-DAWs-18-to-25-1260x1280.png",
            ),
            urlPrefix = "https://www.roundhouse.org.uk/whats-on/",
        )
    }

    @Test
    fun `extracts only Blackhorse Road gigs from the shared Signature Brew events page`() {
        val events = assertScrapesGigs(
            source = SignatureBrewBlackhorseRoadGigsSource(cachedClient()),
            size = 29,
            first = Gig(
                id = GigId(signatureBrewBlackhorseRoad, "https://tixr.com/e/187182"),
                title = "Suntrap Sessions 2026",
                date = LocalDate.of(2026, 7, 27),
                imageUrl = "https://cdn.prod.website-files.com/656d0096af36af2d3cc1cde9/69eb41b1e30251cb31bc631e_7c5b19cb-cd0d-4947-babe-8eed3af2ea87.webp",
            ),
            last = Gig(
                id = GigId(signatureBrewBlackhorseRoad, "https://tixr.com/e/198560"),
                title = "Dig It Up by The Allergies | London",
                date = LocalDate.of(2027, 4, 17),
                imageUrl = "https://cdn.prod.website-files.com/656d0096af36af2d3cc1cde9/6a50e7034103b50e0c99a81a_a357afab-1215-423f-be75-579554bd88fb.webp",
            ),
            urlPrefix = "https://tixr.com/e/",
        )

        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(1)
    }

    @Test
    fun `extracts only Haggerston gigs from the shared Signature Brew events page`() {
        val events = assertScrapesGigs(
            source = SignatureBrewHaggerstonGigsSource(cachedClient()),
            size = 55,
            first = Gig(
                id = GigId(signatureBrewHaggerston, "https://tixr.com/e/186035"),
                title = "Signature Brew Waterfront - Haggerston's Canalside Terrace",
                date = LocalDate.of(2026, 7, 27),
                imageUrl = "https://cdn.prod.website-files.com/656d0096af36af2d3cc1cde9/69e0d1a8d35c853ece44eee1_78d4ade1-c3f1-4277-953e-1bebf8329075.webp",
            ),
            last = Gig(
                id = GigId(signatureBrewHaggerston, "https://tixr.com/e/176800"),
                title = "DUCK & DIVE FESTIVAL 2027 | LONDON",
                date = LocalDate.of(2027, 2, 26),
                imageUrl = "https://cdn.prod.website-files.com/656d0096af36af2d3cc1cde9/6a57a1a53ae33eb0a8b6c494_df8eea81-0037-4763-8407-53609ce233be.webp",
            ),
            urlPrefix = "https://tixr.com/e/",
        )

        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(1)
    }

    @Test
    fun `extracts gig events from the O2 Forum Kentish Town events api, skipping ones with no ticket link`() {
        val events = assertScrapesGigs(
            source = O2ForumKentishTownGigsSource(cachedClient()),
            // 88 events are listed, but one happening today has closed its ticket sales and comes
            // back with no tickets at all, so it has no url to identify or link it by
            size = 87,
            first = Gig(
                id = GigId(o2ForumKentishTown, "https://www.ticketmaster.co.uk/event/3E00648FA8A634C8"),
                title = "Ronnie Wood & His Band featuring Imelda May",
                date = LocalDate.of(2026, 8, 21),
                imageUrl = "https://dynamicmedia.livenationinternational.com/g/v/y/79807d88-4cc2-4da8-acda-d434e0df08b2.jpg",
            ),
            last = Gig(
                id = GigId(o2ForumKentishTown, "https://www.ticketmaster.co.uk/event/3E0065059E6A1198"),
                title = "MASS OF THE FERMENTING DREGS",
                date = LocalDate.of(2027, 10, 14),
                imageUrl = "https://dynamicmedia.livenationinternational.com/t/a/f/03bb4ec9-ed69-4d30-b4d4-1e516b000455.jpg",
            ),
            urlPrefix = "https://www.ticketmaster.co.uk/event/",
        )

        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(3)
    }

    @Test
    fun `extracts gig events from the O2 Academy Brixton events api`() {
        val events = assertScrapesGigs(
            source = O2AcademyBrixtonGigsSource(cachedClient()),
            size = 67,
            first = Gig(
                id = GigId(o2AcademyBrixton, "https://www.ticketmaster.co.uk/event/3E006464ACEB4803"),
                title = "Primus",
                date = LocalDate.of(2026, 8, 19),
                imageUrl = "",
            ),
            last = Gig(
                id = GigId(o2AcademyBrixton, "https://www.ticketmaster.co.uk/event/3E006452FC929180"),
                title = "Loreen: THE WILDFIRE TOUR",
                date = LocalDate.of(2026, 9, 26),
                imageUrl = "https://dynamicmedia.livenationinternational.com/i/l/u/977ca756-1a25-4148-b46a-e2667effd53f.jpg",
            ),
            // unlike every other venue so far, these gigs don't share one url prefix: most sell via
            // ticketmaster but a few link elsewhere entirely, and a couple are http rather than https
            urlPrefix = "http",
        )

        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(1)
        expectThat(events.count { !it.id.url.startsWith("https://www.ticketmaster.co.uk/") }).isEqualTo(3)
    }

    @Test
    fun `extracts gig events from both O2 Academy Islington rooms, which share one listing`() {
        val events = assertScrapesGigs(
            source = O2AcademyIslingtonGigsSource(cachedClient()),
            // the main room and the smaller Academy2 upstairs, listed together as the site does
            size = 83,
            first = Gig(
                id = GigId(o2AcademyIslington, "https://www.ticketmaster.co.uk/event/3E00646A8FB52ACA"),
                title = "OCT (On Company Time) UK Tour",
                date = LocalDate.of(2026, 8, 29),
                imageUrl = "https://dynamicmedia.livenationinternational.com/v/v/w/023063cb-a764-4f67-9d96-075a1bd3d454.jpg",
            ),
            last = Gig(
                id = GigId(o2AcademyIslington, "https://www.ticketmaster.co.uk/event/3E0064F5350835B8"),
                title = "The Reggae Orchestra comes to London",
                date = LocalDate.of(2027, 5, 1),
                imageUrl = "https://dynamicmedia.livenationinternational.com/m/a/b/e51bb674-c586-4164-9477-c725574f74ca.jpg",
            ),
            urlPrefix = "https://www.ticketmaster.co.uk/event/",
        )

        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(4)
    }

    @Test
    fun `extracts gig events from the O2 Shepherd's Bush Empire events api`() {
        val events = assertScrapesGigs(
            source = O2ShepherdsBushEmpireGigsSource(cachedClient()),
            size = 95,
            first = Gig(
                id = GigId(o2ShepherdsBushEmpire, "https://www.ticketmaster.co.uk/event/3E0064AFD611527C"),
                title = "AFI",
                date = LocalDate.of(2026, 8, 20),
                imageUrl = "https://dynamicmedia.livenationinternational.com/s/x/l/353f9994-6437-4ccd-b401-a48c39f23a4b.jpg",
            ),
            last = Gig(
                id = GigId(o2ShepherdsBushEmpire, "https://www.ticketmaster.co.uk/event/3E0064D0EB10676E"),
                title = "Clearwater Creedence Revival: '60th Anniversary of C.C.R' Tour 2027",
                date = LocalDate.of(2027, 11, 27),
                imageUrl = "https://dynamicmedia.livenationinternational.com/e/o/k/21247638-dba8-45ed-9a31-5943a3bf78a6.png",
            ),
            // not the usual "/event/<id>" for every gig here - one is a slug-style ticketmaster
            // link instead, so only the host is common to them all
            urlPrefix = "https://www.ticketmaster.co.uk/",
        )

        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(2)
        expectThat(events.count { !it.id.url.startsWith("https://www.ticketmaster.co.uk/event/") }).isEqualTo(1)
    }

    @Test
    fun `extracts gig events from Union Chapel's what's on page`() {
        val events = assertScrapesGigs(
            source = UnionChapelGigsSource(cachedClient()),
            size = 119,
            first = Gig(
                id = GigId(unionChapel, "https://unionchapel.org.uk/whats-on/mavis-staples-12-aug-2026"),
                title = "MAVIS STAPLES: 12 AUG 2026",
                date = LocalDate.of(2026, 8, 12),
                imageUrl = "https://s3.eu-west-2.amazonaws.com/cdn.unionchapel.org.uk/files/MAVIS%20S.png",
            ),
            last = Gig(
                id = GigId(unionChapel, "https://unionchapel.org.uk/whats-on/fairport-convention-60th-anniversary"),
                title = "Fairport Convention 60th Anniversary",
                date = LocalDate.of(2027, 5, 27),
                imageUrl = "https://s3.eu-west-2.amazonaws.com/cdn.unionchapel.org.uk/files/Fairport%20Convention%2060th%20logo.jpg",
            ),
            urlPrefix = "https://unionchapel.org.uk/whats-on/",
        )

        // the whole listing comes back on one page, with a poster on every card. Document order is
        // *not* chronological - the page sorts client-side, which is why the date is read from
        // data-chron rather than inferred from position as some other venues' listings allow
        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(0)
        expectThat(events.map { it.id.url }.distinct()).hasSize(119)
    }

    @Test
    fun `extracts gig events from Scala's live music category page, following pagination`() {
        val events = assertScrapesGigs(
            source = ScalaGigsSource(cachedClient()),
            size = 55,
            first = Gig(
                id = GigId(scala, "https://scala.co.uk/events/digable-planets/"),
                title = "Digable Planets",
                date = LocalDate.of(2026, 8, 19),
                imageUrl = "https://scala.co.uk/s/wp-content/uploads/2026/03/Digable-Planets-2026_colour-c-Emilio-Herce-scaled-e1774636627462.jpeg",
            ),
            last = Gig(
                id = GigId(scala, "https://scala.co.uk/events/split-the-dealer-deva-st-john/"),
                title = "SPLIT THE DEALER & DEVA ST.JOHN",
                date = LocalDate.of(2027, 5, 20),
                imageUrl = "https://scala.co.uk/s/wp-content/uploads/2026/05/Scala-poster-Prf2_page-0001-1-e1779370004481.jpg",
            ),
            urlPrefix = "https://scala.co.uk/events/",
        )

        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(0)
        // 36 on the first page, 19 on the second - a size assertion alone wouldn't catch double
        // counting if a future site change made the "next" link loop back to page 1
        expectThat(events.map { it.id.url }.distinct()).hasSize(55)
    }

    @Test
    fun `extracts gig events from 229's Dice partner-widget API`() {
        assertScrapesGigs(
            source = TwoTwoNineGigsSource(cachedClient()),
            size = 75,
            first = Gig(
                id = GigId(twoTwoNine, "https://dice.fm/event/lun8-14th-aug-229-london-tickets"),
                title = "LUN8 ",
                date = LocalDate.of(2026, 8, 14),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-06-23/baa8fed2-8ece-4006-83d7-f9610c6622f3.jpg",
            ),
            last = Gig(
                id = GigId(twoTwoNine, "https://dice.fm/event/leo-kottke-9th-jun-229-london-tickets"),
                title = "Leo Kottke",
                date = LocalDate.of(2027, 6, 9),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-06-01/e83611c7-842b-4a07-ae83-b29386d816dc.jpg",
            ),
            urlPrefix = "https://dice.fm/event/",
        )
    }

    @Test
    fun `extracts gig events from Alexandra Palace's what's on page`() {
        val events = assertScrapesGigs(
            source = AlexandraPalaceGigsSource(cachedClient()),
            size = 41,
            first = Gig(
                id = GigId(alexandraPalace, "https://www.alexandrapalace.com/whats-on/upside-down-london/"),
                // trailing   (narrow no-break space), not a plain space - it's what the
                // page's own title text actually contains, confirmed character-by-character
                // against a failed run before this literal was written
                title = "Upside Down London ",
                date = LocalDate.of(2026, 8, 1),
                imageUrl = "https://www.alexandrapalace.com/wp-content/uploads/2026/05/pl-udl-approved-media-assets-14-of-17-marked-2048x1536.jpg",
            ),
            last = Gig(
                id = GigId(alexandraPalace, "https://www.alexandrapalace.com/whats-on/kaleidoscope-festival-2/"),
                title = "Kaleidoscope Festival",
                date = LocalDate.of(2027, 7, 10),
                imageUrl = "https://www.alexandrapalace.com/wp-content/uploads/2026/07/Kaleidescope-11.07.26-www.harbinson.uk-7159-2048x1366.jpg",
            ),
            urlPrefix = "https://www.alexandrapalace.com/whats-on/",
        )

        // srcset's widest entry is used over the img tag's own 650px src, and the two events with
        // no srcset (only a plain src) still resolve rather than falling back to blank
        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(0)
    }

    @Test
    fun `resolves the start date of a range, including one that crosses a calendar year`() {
        fun eventPage(dates: String) = """
            <div class="event_card_wrapper">
                <div class="event_img proportional_container"></div>
                <header><p class="dates uc"><strong>$dates</strong></p>
                <a href="https://example.com/gig" class="event_target"><h3>Gig</h3></a></header>
            </div>
        """.trimIndent()

        fun startDateOf(dates: String): LocalDate {
            val fakeClient: HttpHandler = { Response(OK).body(eventPage(dates)) }
            return AlexandraPalaceGigsSource(fakeClient).latestGigs().single().date
        }

        expectThat(startDateOf("21 Aug 2026")).isEqualTo(LocalDate.of(2026, 8, 21))
        // same month range - the year and month are only written once, on the end day
        expectThat(startDateOf("1 - 9 Aug 2026")).isEqualTo(LocalDate.of(2026, 8, 1))
        // cross-month range within one year - both start and end take the written year
        expectThat(startDateOf("19 Sep - 5 Dec 2026")).isEqualTo(LocalDate.of(2026, 9, 19))
        // cross-month range crossing new year's day - the written year belongs to the end date
        // (Jan 2027), so the start date (Dec) must roll back to the year before it
        expectThat(startDateOf("11 Dec - 3 Jan 2027")).isEqualTo(LocalDate.of(2026, 12, 11))
    }

    @Test
    fun `extracts gig events from Paper Dress Vintage's by-night page`() {
        assertScrapesGigs(
            source = PaperDressVintageGigsSource(cachedClient()),
            size = 46,
            first = Gig(
                id = GigId(paperDressVintage, "https://paperdressvintage.co.uk/?p=18710"),
                title = "That 70s Night ft. Vintage Voltage",
                date = LocalDate.of(2026, 8, 14),
                imageUrl = "http://paperdressvintage.co.uk/wp-content/uploads/2026/07/poster-aug-14th-pd1-scaled.jpg",
            ),
            last = Gig(
                id = GigId(paperDressVintage, "https://paperdressvintage.co.uk/?p=18815"),
                title = "Sam Scherdel",
                date = LocalDate.of(2026, 12, 10),
                imageUrl = "http://paperdressvintage.co.uk/wp-content/uploads/2026/07/Sam-Scherdel.jpg",
            ),
            urlPrefix = "https://paperdressvintage.co.uk/",
        )
    }
}
