import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.jsoup.Jsoup
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.containsExactly
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GigsSourceTest {

    private fun assertScrapesGigs(source: GigsSource, size: Int, first: Gig, last: Gig, urlPrefix: String): List<Gig> {
        val events = source.latestGigs()
        events.forEach { println(it) }

        expectThat(events).hasSize(size)
        // a description is its whole event page's text, so the per-venue expectations mask it rather
        // than carrying thousands of characters each - what's extracted from a page has its own tests
        expectThat(events.first().copy(description = "")).isEqualTo(first)
        expectThat(events.last().copy(description = "")).isEqualTo(last)
        expectThat(events.all { it.id.url.startsWith(urlPrefix) }).isTrue()
        expectThat(events.all { it.id.venueId == first.id.venueId }).isTrue()

        return events
    }

    @Test
    fun `extracts gig events from news page`() {
        val events = assertScrapesGigs(
            source = CartAndHorsesGigsSource(cachedClient(), year = 2026),
            size = 21,
            first = Gig(
                id = GigId(cartAndHorses.id, "https://www.cartandhorses.london/news-offers-events/523846-three-birds-whisper-the-positive-rebellion-tour-uk-2026-psychedelic-skies-borderline/"),
                title = GigTitle("THREE BIRDS WHISPER - The Positive Rebellion Tour UK 2026 + PSYCHEDELIC SKIES + BORDERLINE"),
                date = LocalDate.of(2026, 8, 8),
                imageUrl = "https://www.useyourlocal.com/imgs/pub_events/sr@1x/240726-012017_threebirds-upd.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(cartAndHorses.id, "https://www.cartandhorses.london/news-offers-events/517524-jbm-presents-smells-like-nirvana/"),
                title = GigTitle("Jbm presents SMELLS LIKE NIRVANA"),
                date = LocalDate.of(2026, 10, 10),
                imageUrl = "https://www.useyourlocal.com/imgs/pub_events/sr@1x/270126-043912_smelllike.jpg",
                description = "",
            ),
            urlPrefix = "https://www.cartandhorses.london/",
        )

        expectThat(events.take(3).map { it.date })
            .containsExactly(LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 15))

        val titles = events.map { it.title.value }
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
            <!-- the same body answers this source's event-page requests, which now have to yield a
                 description rather than being allowed to come back empty -->
            <div class="page_content_inner">Doom night, doors 7pm.</div>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }

        val events = CartAndHorsesGigsSource(fakeClient, year = 2026).latestGigs()

        expectThat(events.map { it.date.year }).containsExactly(2026, 2027, 2027)
    }

    // the page opens on the current month, so all but the first two months here come from the
    // dropdown's own admin-ajax call rather than the page itself
    @Test
    fun `extracts gig events from New Cross Inn gigs page, following the months dropdown`() {
        val events = assertScrapesGigs(
            source = NewCrossInnGigsSource(cachedClient()),
            size = 118,
            first = Gig(
                id = GigId(newCrossInn.id, "https://pit.live/events/greenhat"),
                title = GigTitle("GREENHAT"),
                date = LocalDate.of(2026, 8, 8),
                imageUrl = "https://pit.live/uploads/user/2026/07/07/640x480/5d05ygXA94bMG95I.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(newCrossInn.id, "https://pit.live/events/level-up-festival-7"),
                title = GigTitle("Level Up Festival 7"),
                date = LocalDate.of(2027, 7, 23),
                imageUrl = "https://pit.live/uploads/user/2026/07/24/640x480/t8YfuAmMlTMW6ilv.jpg",
                description = "",
            ),
            urlPrefix = "https://pit.live/events/",
        )

        // a gig five months past what the page itself lists, and the one that showed the dropdown
        // was being missed - the page opens on August, and this is only in the February fragment
        expectThat(events.map { it.id.url }).contains("https://pit.live/events/ghost-uk-1")
        // the month the page opens on is in the dropdown too, so its gigs arrive from both
        expectThat(events.map { it.id.url }.distinct().size).isEqualTo(events.size)
    }

    // its event pages are all empty, so unlike the other Squarespace venues the description comes off
    // the listing itself - asserted separately from first/last, since the excerpts run to a couple of
    // thousand characters each
    @Test
    fun `extracts gig events from The Fiddler's Elbow whos-playing page`() {
        val events = FiddlersElbowGigsSource(cachedClient()).latestGigs()
        events.forEach { println(it) }

        expectThat(events).hasSize(10)
        expectThat(events.first().copy(description = "")).isEqualTo(
            Gig(
                id = GigId(fiddlersElbow.id, "https://www.thefiddlerselbow.co.uk/whos-playing/moonpunx-16-matinee1682026"),
                title = GigTitle("MoonPunx 16 Matinee"),
                date = LocalDate.of(2026, 8, 16),
                imageUrl = "https://images.squarespace-cdn.com/content/v1/56eabd14b6aa60459af3a4f2/1786573507310-PG9FBW6TLI2B45R0QU3D/Unknown-2.png",
                description = "",
            ),
        )
        expectThat(events.last().copy(description = "")).isEqualTo(
            Gig(
                id = GigId(fiddlersElbow.id, "https://www.thefiddlerselbow.co.uk/whos-playing/neo-rockabilly-explosion-3-the-neutronz-wigsville-spliffs-dj-chris-setzer2692026"),
                title = GigTitle("NEO ROCKABILLY EXPLOSION #3 The Neutronz, Wigsville Spliffs, DJ Chris Setzer."),
                date = LocalDate.of(2026, 9, 26),
                imageUrl = "https://images.squarespace-cdn.com/content/v1/56eabd14b6aa60459af3a4f2/1785973667820-T4D2VFAJ8AY9UAJQ4GHP/69613b4679576_event.jpeg",
                description = "",
            ),
        )
        expectThat(events.all { it.id.url.startsWith("https://www.thefiddlerselbow.co.uk/whos-playing/") }).isTrue()
        // the whole reason the description comes off the listing: 80 chars is where the classifier
        // gives up on the text and judges the poster image instead
        expectThat(events.all { it.description.length > 80 }).isTrue()
        expectThat(events.first().description).contains("Steam Kittens are a punk band")
    }

    @Test
    fun `takes only the excerpt as a listing-described gig's text, not the item's own meta`() {
        val html = """
            <div class="eventlist eventlist--upcoming">
              <article class="eventlist-event eventlist-event--upcoming">
                <a href="/whos-playing/some-gig" class="eventlist-column-thumbnail"><img src="https://example.com/poster.jpg"></a>
                <h1 class="eventlist-title"><a href="/whos-playing/some-gig" class="eventlist-title-link">SOME GIG</a></h1>
                <time class="event-date" datetime="2026-09-01">Tuesday 1 September 2026</time>
                <div class="eventlist-excerpt"><p>Doom metal night with support.</p></div>
                <ul class="eventlist-meta event-meta">
                  <li class="eventlist-meta-item eventlist-meta-address">
                    <span class="eventlist-meta-address-line">1 Malden Road</span>
                    <a class="eventlist-meta-address-maplink">(map)</a>
                  </li>
                </ul>
                <a class="eventlist-button">View Event &#8594;</a>
              </article>
            </div>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }

        val events = SquarespaceEventsGigsSource(
            fakeClient,
            url = "https://example.com/whos-playing",
            venue = fiddlersElbow,
            descriptionFrom = SquarespaceDescription.ListingExcerpt,
        ).latestGigs()

        expectThat(events.single().description).isEqualTo("Doom metal night with support.")
    }

    @Test
    fun `extracts gig events from Our Black Heart events page`() {
        assertScrapesGigs(
            source = OurBlackHeartGigsSource(cachedClient()),
            size = 50,
            first = Gig(
                id = GigId(ourBlackHeart.id, "https://www.ourblackheart.com/events/2026/8/8/you-win-again-gravity"),
                title = GigTitle("YOU WIN AGAIN GRAVITY"),
                date = LocalDate.of(2026, 8, 8),
                imageUrl = "https://images.squarespace-cdn.com/content/v1/5486e6cde4b0d80114155bf4/1782745761879-UVSUIG341XJIY3MEB9MI/LBPHOTO%2B-%2B%2BYou%2BWin%2BAgain%2BGravity%2B-%2BPromo%2B-%2B20.10.2024%2B6.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(ourBlackHeart.id, "https://www.ourblackheart.com/events/2027/3/19/necropolis-vol-iii"),
                title = GigTitle("NECROPOLIS VOL. III"),
                date = LocalDate.of(2027, 3, 19),
                imageUrl = "https://images.squarespace-cdn.com/content/v1/5486e6cde4b0d80114155bf4/1781025655512-MHR6PMWPOOE3TJFOSWAB/Necropolis_2027_IG_Feed_Poster_2nd_announcement%2B%25281%2529.jpg",
                description = "",
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
                id = GigId(theUnderworld.id, "https://www.theunderworldcamden.co.uk/event/the-partisans-8th-aug-the-underworld-london-tickets/"),
                title = GigTitle("THE PARTISANS"),
                date = LocalDate.of(2026, 8, 8),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-04-15/644411f7-5f86-484c-b29b-b71dc309b89e.jpg?rect=734%2C0%2C2682%2C2682",
                description = "",
            ),
            last = Gig(
                id = GigId(theUnderworld.id, "https://www.theunderworldcamden.co.uk/event/alive-a-tribute-to-pearl-jam-20th-nov-the-underworld-london-tickets/"),
                title = GigTitle("ALIVE, A TRIBUTE TO PEARL JAM"),
                date = LocalDate.of(2027, 12, 4),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-02-10/cf613856-3e58-41a8-b0f0-af044c77c97b.jpg?rect=228%2C0%2C2045%2C2045",
                description = "",
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
                id = GigId(theDome.id, "https://www.domelondon.co.uk/whatson/08/08-battlesnake"),
                title = GigTitle("BATTLESNAKE"),
                date = LocalDate.of(2026, 8, 8),
                imageUrl = "https://images.squarespace-cdn.com/content/v1/6708f569091ee6412723acb9/1777381588492-CAQQZA5RRSD026668882/Cathedral%2BColour.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(theDome.id, "https://www.domelondon.co.uk/whatson/03/07-draconian"),
                title = GigTitle("DRACONIAN"),
                date = LocalDate.of(2027, 3, 7),
                imageUrl = "https://images.squarespace-cdn.com/content/v1/6708f569091ee6412723acb9/1771509016965-K3W9K2G4J853EZ97RETL/Draconian+done-56+%28low+res%29.jpg",
                description = "",
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
                id = GigId(blondiesBreweryTaproom.id, "https://dice.fm/event/2wqb7p-its-never-over-jeff-buckley-screening-12th-aug-blondies-brewery-london-tickets"),
                title = GigTitle("It's Never Over, Jeff Buckley > Screening"),
                date = LocalDate.of(2026, 8, 12),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-08-03/6088fc1d-076f-4946-b1d6-342519c36355.jpg?rect=0%2C49%2C2159%2C2159",
                description = "",
            ),
            last = Gig(
                id = GigId(blondiesBreweryTaproom.id, "https://dice.fm/event/8eq9dw-forlorn-birdwitch-27th-nov-blondies-brewery-london-tickets"),
                title = GigTitle("FORLORN / BIRDWITCH"),
                date = LocalDate.of(2026, 11, 27),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-07-13/d2e1f34c-9f57-4a47-811c-5e6d4efbc40a.jpg?rect=0%2C135%2C1080%2C1080",
                description = "",
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
                id = GigId(blondiesBar.id, "https://dice.fm/event/av57g7-midweek-mayhem-4-pints-all-night-12th-aug-blondies-london-tickets"),
                title = GigTitle("Midweek Mayhem – £4 Pints All Night"),
                date = LocalDate.of(2026, 8, 12),
                imageUrl = "https://dice-media.imgix.net/attachments/2025-07-23/03c4258d-44cc-4c61-8612-5d5495f6684b.jpg?rect=0%2C0%2C4385%2C4385",
                description = "",
            ),
            last = Gig(
                id = GigId(blondiesBar.id, "https://dice.fm/event/bboxdm-1986-support-5th-dec-blondies-london-tickets"),
                title = GigTitle("1986 + Support"),
                date = LocalDate.of(2026, 12, 5),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-04-27/4c005268-bc5b-43c7-a69f-8117623d0232.jpg?rect=0%2C0%2C2048%2C2048",
                description = "",
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
                id = GigId(helgis.id, "https://dice.fm/event/avrpa2-sceptocrypt-in-gods-way-cariad-14th-aug-helgis-london-tickets"),
                title = GigTitle("Sceptocrypt + In Gods Way + Cariad"),
                date = LocalDate.of(2026, 8, 14),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-08-09/bcabb7e3-0777-4c15-929c-9192d05503fb.jpg?rect=0%2C32%2C1187%2C1187",
                description = "",
            ),
            last = Gig(
                id = GigId(helgis.id, "https://dice.fm/event/xedvra-holocaust-hyena-14th-nov-helgis-london-tickets"),
                title = GigTitle("HOLOCAUST + HYENA"),
                date = LocalDate.of(2026, 11, 14),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-04-07/cdca232f-2df2-41a6-a2b1-cdaa5c827aa3.jpg?rect=0%2C135%2C1080%2C1080",
                description = "",
            ),
            urlPrefix = "https://dice.fm/event/",
        )
    }

    @Test
    fun `extracts gig events from Barfly Camden's dice_fm venue page`() {
        assertScrapesGigs(
            source = BarflyCamdenGigsSource(cachedClient()),
            size = 24,
            first = Gig(
                id = GigId(barflyCamden.id, "https://dice.fm/event/xe37pm-propaganda-indie-club-night-at-barfly-15th-aug-barfly-camden-london-tickets"),
                title = GigTitle("Propaganda - Indie Club Night at Barfly!"),
                date = LocalDate.of(2026, 8, 15),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-08-06/1c3136b1-128c-4ca2-ac03-809114ab7663.jpg?rect=0%2C0%2C1080%2C1080",
                description = "",
            ),
            last = Gig(
                id = GigId(barflyCamden.id, "https://dice.fm/event/k6lw79-forever-never-13th-feb-barfly-camden-london-tickets"),
                title = GigTitle("Forever Never"),
                date = LocalDate.of(2027, 2, 13),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-06-16/6f0ca902-24a7-4421-a288-f70e55030959.jpg?rect=0%2C0%2C1400%2C1400",
                description = "",
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
                id = GigId(electricBallroom.id, "https://electricballroom.co.uk/lion-babe/"),
                title = GigTitle("Lion Babe – RESCHEDULED!"),
                date = LocalDate.of(2026, 8, 13),
                imageUrl = "https://electricballroom.co.uk/wp-content/uploads/2026/07/LION-BABE-.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(electricBallroom.id, "https://electricballroom.co.uk/indiepalooza-tribute-killers-v-monkeys-v-fender-v-oasis-v-kasabian-v-kaiser/"),
                title = GigTitle("Indiepalooza Tribute – Killers v Monkeys v Fender v Oasis v Kasabian v Kaiser"),
                date = LocalDate.of(2027, 6, 19),
                imageUrl = "https://electricballroom.co.uk/wp-content/uploads/2026/06/Indiepalooza-2027.jpg",
                description = "",
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
                id = GigId(dingwalls.id, "https://dingwalls.com/gig/root-company/"),
                title = GigTitle("BANG YONGGUK"),
                date = LocalDate.of(2026, 9, 2),
                imageUrl = "https://dingwalls.com/wp-content/uploads/elementor/thumbs/PP-5-ropdtf0hg2d9yqdycam42ynoc5vdz4n4gsylj8c3l8.png",
                description = "",
            ),
            last = Gig(
                id = GigId(dingwalls.id, "https://dingwalls.com/gig/rock-for-hope-2/"),
                title = GigTitle("Rock For Hope"),
                date = LocalDate.of(2026, 11, 7),
                imageUrl = "https://dingwalls.com/wp-content/uploads/elementor/thumbs/PP-27-rr5voszodg8dz4qw6s0thhnj6cm8eai4qgy0bw9ru4.jpg",
                description = "",
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
                id = GigId(theGarage.id, "https://www.thegarage.london/gigs/when-chai-met-toast/"),
                title = GigTitle("WHEN CHAI MET TOAST"),
                date = LocalDate.of(2026, 8, 14),
                imageUrl = "",
                description = "",
            ),
            last = Gig(
                id = GigId(theGarage.id, "https://www.thegarage.london/gigs/black-altar-xxx-anniversary-show-the-garage-london-tickets-2026/"),
                title = GigTitle("BLACK ALTAR - XXX ANNIVERSARY SHOW"),
                date = LocalDate.of(2026, 10, 31),
                imageUrl = "https://www.thegarage.london/wp-content/uploads/2026/07/XXXYears-Poster-4-insta-819x1024.jpg",
                description = "",
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
                id = GigId(theGrace.id, "https://www.thegrace.london/gigs/flamebearer-the-grace-london-tickets-2026/"),
                title = GigTitle("FLAMEBEARER"),
                date = LocalDate.of(2026, 8, 14),
                imageUrl = "https://www.thegrace.london/wp-content/uploads/2026/05/FLAMEBEARER_IGNITER_ALBUM_LAUNCH_POSTER_SQUARE_v3_MED_RES_RGB-1-1024x1024.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(theGrace.id, "https://www.thegrace.london/gigs/dreamdnvr-the-grace-london-tickets-2026/"),
                title = GigTitle("DREAMDNVR"),
                date = LocalDate.of(2026, 10, 31),
                imageUrl = "https://www.thegrace.london/wp-content/uploads/2026/05/PRESS-PHOTO-DD-3-1-1024x683.jpg",
                description = "",
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
            <!-- the same body answers the event-page request, which now has to yield a description -->
            <section class="single-article single-article--contains-list">
              <div class="single-article__content"><p>Sold out gig, doors 7pm.</p></div>
            </section>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }

        val events = DhpVenueGigsSource(fakeClient, url = "https://example.com/whats-on/", venue = Venue(VenueId("some-venue"), "Some Venue")).latestGigs()

        expectThat(events).containsExactly(
            Gig(
                id = GigId(VenueId("some-venue"), "https://example.com/gigs/sold-out-gig/"),
                title = GigTitle("SOLD OUT GIG"),
                date = LocalDate.of(2026, 10, 3),
                imageUrl = "https://example.com/poster.jpg",
                description = "Sold out gig, doors 7pm.",
            ),
        )
    }

    @Test
    fun `extracts gig events from the Roundhouse whats-on page`() {
        assertScrapesGigs(
            source = RoundhouseGigsSource(cachedClient()),
            size = 9,
            first = Gig(
                id = GigId(roundhouse.id, "https://www.roundhouse.org.uk/whats-on/cf-kristen-schaal-the-legend/"),
                title = GigTitle("Kristen Schaal: The Legend of Crystal Shell"),
                date = LocalDate.of(2026, 8, 17),
                imageUrl = "https://assets.roundhouse.org.uk/app/uploads/2026/04/Kristen-Schaal-4.png",
                description = "",
            ),
            last = Gig(
                id = GigId(roundhouse.id, "https://www.roundhouse.org.uk/whats-on/roger-taylor/"),
                title = GigTitle("Roger Taylor"),
                date = LocalDate.of(2026, 9, 28),
                imageUrl = "https://assets.roundhouse.org.uk/app/uploads/2026/06/Roger_Taylor_London_1260x1280.jpg",
                description = "",
            ),
            urlPrefix = "https://www.roundhouse.org.uk/whats-on/",
        )
    }

    @Test
    fun `extracts Blackhorse Road gigs from the Dice partner API`() {
        assertScrapesGigs(
            source = SignatureBrewBlackhorseRoadGigsSource(cachedClient()),
            size = 23,
            first = Gig(
                id = GigId(signatureBrewBlackhorseRoad.id, "https://dice.fm/event/disco-2000-summer-yard-party-london-23rd-aug-signature-brew-blackhorse-road-london-tickets"),
                title = GigTitle("Disco 2000 Summer Yard Party | London"),
                date = LocalDate.of(2026, 8, 23),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-06-18/c2d85f59-1c17-4a96-8291-77270ebeba4b.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(signatureBrewBlackhorseRoad.id, "https://dice.fm/event/dig-it-up-by-the-allergies-london-17th-apr-signature-brew-blackhorse-road-london-tickets"),
                title = GigTitle("Dig It Up by The Allergies | London"),
                date = LocalDate.of(2027, 4, 17),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-07-10/63524421-198d-4192-bbd4-44bd063bf8e5.jpg",
                description = "",
            ),
            urlPrefix = "https://dice.fm/event/",
        )
    }

    @Test
    fun `extracts Haggerston gigs from the Dice partner API`() {
        assertScrapesGigs(
            source = SignatureBrewHaggerstonGigsSource(cachedClient()),
            size = 46,
            first = Gig(
                id = GigId(signatureBrewHaggerston.id, "https://dice.fm/event/papangu-zeta-meiotempo-london-18th-aug-signature-brew-haggerston-london-tickets"),
                title = GigTitle("Papangu + Zeta + Meiotempo | London"),
                date = LocalDate.of(2026, 8, 18),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-07-23/7a6ab6c3-0bb0-468a-b158-60bdc49f59c7.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(signatureBrewHaggerston.id, "https://dice.fm/event/duck-dive-festival-2027-london-26th-feb-signature-brew-haggerston-london-tickets"),
                title = GigTitle("DUCK & DIVE FESTIVAL 2027 | LONDON"),
                date = LocalDate.of(2027, 2, 26),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-07-15/bab2924f-aa0a-4549-81e9-818da1a845b1.jpg",
                description = "",
            ),
            urlPrefix = "https://dice.fm/event/",
        )
    }

    @Test
    fun `extracts gig events from the O2 Forum Kentish Town events api, skipping ones with no ticket link`() {
        val events = assertScrapesGigs(
            source = O2ForumKentishTownGigsSource(cachedClient()),
            // 88 events are listed, but one happening today has closed its ticket sales and comes
            // back with no tickets at all, so it has no url to identify or link it by
            size = 87,
            first = Gig(
                id = GigId(o2ForumKentishTown.id, "https://www.ticketmaster.co.uk/event/3E00648FA8A634C8"),
                title = GigTitle("Ronnie Wood & His Band featuring Imelda May"),
                date = LocalDate.of(2026, 8, 21),
                imageUrl = "https://dynamicmedia.livenationinternational.com/g/v/y/79807d88-4cc2-4da8-acda-d434e0df08b2.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(o2ForumKentishTown.id, "https://www.ticketmaster.co.uk/event/3E0065059E6A1198"),
                title = GigTitle("MASS OF THE FERMENTING DREGS"),
                date = LocalDate.of(2027, 10, 14),
                imageUrl = "https://dynamicmedia.livenationinternational.com/t/a/f/03bb4ec9-ed69-4d30-b4d4-1e516b000455.jpg",
                description = "",
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
                id = GigId(o2AcademyBrixton.id, "https://www.ticketmaster.co.uk/event/3E006464ACEB4803"),
                title = GigTitle("Primus"),
                date = LocalDate.of(2026, 8, 19),
                imageUrl = "",
                description = "",
            ),
            last = Gig(
                id = GigId(o2AcademyBrixton.id, "https://www.ticketmaster.co.uk/event/3E006452FC929180"),
                title = GigTitle("Loreen: THE WILDFIRE TOUR"),
                date = LocalDate.of(2026, 9, 26),
                imageUrl = "https://dynamicmedia.livenationinternational.com/i/l/u/977ca756-1a25-4148-b46a-e2667effd53f.jpg",
                description = "",
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
                id = GigId(o2AcademyIslington.id, "https://www.ticketmaster.co.uk/event/3E00646A8FB52ACA"),
                title = GigTitle("OCT (On Company Time) UK Tour"),
                date = LocalDate.of(2026, 8, 29),
                imageUrl = "https://dynamicmedia.livenationinternational.com/v/v/w/023063cb-a764-4f67-9d96-075a1bd3d454.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(o2AcademyIslington.id, "https://www.ticketmaster.co.uk/event/3E0064F5350835B8"),
                title = GigTitle("The Reggae Orchestra comes to London"),
                date = LocalDate.of(2027, 5, 1),
                imageUrl = "https://dynamicmedia.livenationinternational.com/m/a/b/e51bb674-c586-4164-9477-c725574f74ca.jpg",
                description = "",
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
                id = GigId(o2ShepherdsBushEmpire.id, "https://www.ticketmaster.co.uk/event/3E0064AFD611527C"),
                title = GigTitle("AFI"),
                date = LocalDate.of(2026, 8, 20),
                imageUrl = "https://dynamicmedia.livenationinternational.com/s/x/l/353f9994-6437-4ccd-b401-a48c39f23a4b.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(o2ShepherdsBushEmpire.id, "https://www.ticketmaster.co.uk/event/3E0064D0EB10676E"),
                title = GigTitle("Clearwater Creedence Revival: '60th Anniversary of C.C.R' Tour 2027"),
                date = LocalDate.of(2027, 11, 27),
                imageUrl = "https://dynamicmedia.livenationinternational.com/e/o/k/21247638-dba8-45ed-9a31-5943a3bf78a6.png",
                description = "",
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
                id = GigId(unionChapel.id, "https://unionchapel.org.uk/whats-on/mavis-staples-12-aug-2026"),
                title = GigTitle("MAVIS STAPLES: 12 AUG 2026"),
                date = LocalDate.of(2026, 8, 12),
                imageUrl = "https://s3.eu-west-2.amazonaws.com/cdn.unionchapel.org.uk/files/MAVIS%20S.png",
                description = "",
            ),
            last = Gig(
                id = GigId(unionChapel.id, "https://unionchapel.org.uk/whats-on/fairport-convention-60th-anniversary"),
                title = GigTitle("Fairport Convention 60th Anniversary"),
                date = LocalDate.of(2027, 5, 27),
                imageUrl = "https://s3.eu-west-2.amazonaws.com/cdn.unionchapel.org.uk/files/Fairport%20Convention%2060th%20logo.jpg",
                description = "",
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
                id = GigId(scala.id, "https://scala.co.uk/events/digable-planets/"),
                title = GigTitle("Digable Planets"),
                date = LocalDate.of(2026, 8, 19),
                imageUrl = "https://scala.co.uk/s/wp-content/uploads/2026/03/Digable-Planets-2026_colour-c-Emilio-Herce-scaled-e1774636627462.jpeg",
                description = "",
            ),
            last = Gig(
                id = GigId(scala.id, "https://scala.co.uk/events/split-the-dealer-deva-st-john/"),
                title = GigTitle("SPLIT THE DEALER & DEVA ST.JOHN"),
                date = LocalDate.of(2027, 5, 20),
                imageUrl = "https://scala.co.uk/s/wp-content/uploads/2026/05/Scala-poster-Prf2_page-0001-1-e1779370004481.jpg",
                description = "",
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
                id = GigId(twoTwoNine.id, "https://dice.fm/event/lun8-14th-aug-229-london-tickets"),
                title = GigTitle("LUN8 "),
                date = LocalDate.of(2026, 8, 14),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-06-23/baa8fed2-8ece-4006-83d7-f9610c6622f3.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(twoTwoNine.id, "https://dice.fm/event/leo-kottke-9th-jun-229-london-tickets"),
                title = GigTitle("Leo Kottke"),
                date = LocalDate.of(2027, 6, 9),
                imageUrl = "https://dice-media.imgix.net/attachments/2026-06-01/e83611c7-842b-4a07-ae83-b29386d816dc.jpg",
                description = "",
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
                id = GigId(alexandraPalace.id, "https://www.alexandrapalace.com/whats-on/upside-down-london/"),
                // trailing   (narrow no-break space), not a plain space - it's what the
                // page's own title text actually contains, confirmed character-by-character
                // against a failed run before this literal was written
                title = GigTitle("Upside Down London "),
                date = LocalDate.of(2026, 8, 1),
                imageUrl = "https://www.alexandrapalace.com/wp-content/uploads/2026/05/pl-udl-approved-media-assets-14-of-17-marked-2048x1536.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(alexandraPalace.id, "https://www.alexandrapalace.com/whats-on/kaleidoscope-festival-2/"),
                title = GigTitle("Kaleidoscope Festival"),
                date = LocalDate.of(2027, 7, 10),
                imageUrl = "https://www.alexandrapalace.com/wp-content/uploads/2026/07/Kaleidescope-11.07.26-www.harbinson.uk-7159-2048x1366.jpg",
                description = "",
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
            <!-- the same body answers the event-page request, which now has to yield a description -->
            <div class="ap_text_block">An evening of something.</div>
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
                id = GigId(paperDressVintage.id, "https://paperdressvintage.co.uk/?p=18710"),
                title = GigTitle("That 70s Night ft. Vintage Voltage"),
                date = LocalDate.of(2026, 8, 14),
                imageUrl = "http://paperdressvintage.co.uk/wp-content/uploads/2026/07/poster-aug-14th-pd1-scaled.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(paperDressVintage.id, "https://paperdressvintage.co.uk/?p=18815"),
                title = GigTitle("Sam Scherdel"),
                date = LocalDate.of(2026, 12, 10),
                imageUrl = "http://paperdressvintage.co.uk/wp-content/uploads/2026/07/Sam-Scherdel.jpg",
                description = "",
            ),
            urlPrefix = "https://paperdressvintage.co.uk/",
        )
    }

    @Test
    fun `extracts gig events from Islington Assembly Hall's events page, following pagination`() {
        val events = assertScrapesGigs(
            source = IslingtonAssemblyHallGigsSource(cachedClient(), year = 2026),
            size = 74,
            first = Gig(
                id = GigId(islingtonAssemblyHall.id, "https://islingtonassemblyhall.co.uk/events/horsegirl-21st-aug-islington-assembly-hall-london-tickets/"),
                title = GigTitle("Horsegirl"),
                date = LocalDate.of(2026, 8, 21),
                imageUrl = "https://islingtonassemblyhall.co.uk/app/uploads/2026/03/16a4e407-24c9-482a-9f0b-8f8b7812520a.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(islingtonAssemblyHall.id, "https://islingtonassemblyhall.co.uk/events/seckou-keita-and-the-homeland-band-featuring-special-guests-30th-anniversary-tour-10th-feb-islington-assembly-hall-london-tickets/"),
                title = GigTitle("Seckou Keita and The Homeland Band ft Special Guests: 30th Anniversary Tour"),
                date = LocalDate.of(2027, 11, 28),
                imageUrl = "https://islingtonassemblyhall.co.uk/app/uploads/2025/11/Untitled-design-1.jpg",
                description = "",
            ),
            urlPrefix = "https://islingtonassemblyhall.co.uk/events/",
        )

        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(0)
        // eighteen to a page over five pages - the last page's "Next" is gone rather than disabled,
        // but a size assertion alone wouldn't catch a future change that looped back to page 1
        expectThat(events.map { it.id.url }.distinct()).hasSize(74)
        // the listing crosses from December into January mid-page-4, which is the only place the
        // year advances - the last gig's own page dates it 28/11/2027, and 28 Nov 2027 is the Sunday
        // its card says it is
        expectThat(events.filter { it.date.year == 2027 }).hasSize(13)
    }

    @Test
    fun `extracts gig events from Windmill Brixton's listings page, following pagination`() {
        val events = assertScrapesGigs(
            source = WindmillBrixtonGigsSource(cachedClient()),
            size = 27,
            first = Gig(
                id = GigId(windmillBrixton.id, "https://www.windmillbrixton.co.uk/events/2026-08-14-house-arrest-george-jr-and-the-9-slash-11s-rampressure-skunkworm-the-windmill"),
                title = GigTitle("House Arrest, George Jr & the 9/11s, Rampressure, Skunkworm"),
                date = LocalDate.of(2026, 8, 14),
                imageUrl = "https://musicglue-images-prod.global.ssl.fastly.net/windmill-brixton/event/2026-08-14-house-arrest-george-jr-and-the-9-slash-11s-rampressure-skunkworm-the-windmill?u=aHR0cHM6Ly9tdXNpY2dsdWUtdXNlci1hcHAtcC01LXAuczMuYW1hem9uYXdzLmNvbS9vcmlnaW5hbHMvMzE1MDZlNzEtNTRiZC00YmQzLTk3Y2YtZmE3ZWIxNTUwYzFm&v=2",
                description = "",
            ),
            last = Gig(
                id = GigId(windmillBrixton.id, "https://www.windmillbrixton.co.uk/events/2026-11-19-grommet-the-windmill"),
                title = GigTitle("Grommet"),
                date = LocalDate.of(2026, 11, 19),
                imageUrl = "https://musicglue-images-prod.global.ssl.fastly.net/windmill-brixton/event/2026-11-19-grommet-the-windmill?u=aHR0cHM6Ly9tdXNpY2dsdWUtdXNlci1hcHAtcC00LXAuczMuYW1hem9uYXdzLmNvbS9vcmlnaW5hbHMvYmJmYjAwYzItMDk2Ny00NmM4LWJiZjYtNmEyZDBhZDU3MTY4&v=2",
                description = "",
            ),
            urlPrefix = "https://www.windmillbrixton.co.uk/events/",
        )

        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(0)
        // 24 on the first page, 3 on the second - the last page still carries a "Next" link, so a
        // size assertion alone wouldn't catch following it back into the page just read
        expectThat(events.map { it.id.url }.distinct()).hasSize(27)
        // the two untitled (halo) shows are consecutive nights, and the cards only say "Mon, Sep 14"
        // and "Tue, Sep 15" - the year comes from the event path
        expectThat(events.filter { it.title.value == "untitled (halo)" }.map { it.date })
            .containsExactly(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15))
    }

    @Test
    fun `extracts gig events from Eventim Apollo's events page`() {
        val events = assertScrapesGigs(
            source = EventimApolloGigsSource(cachedClient()),
            size = 83,
            first = Gig(
                id = GigId(eventimApollo.id, "https://www.eventimapollo.com/events/venue-tours"),
                title = GigTitle("Eventim Apollo OPEN: Venue Tours"),
                date = LocalDate.of(2026, 8, 16),
                imageUrl = "https://aeg-media-assets.b-cdn.net/eventim/images/0e5e0082-1ed9-4180-97a0-5cb66a922ce7.jpg?width=768&height=768&focus_crop=1200,1200,0.5,0.5",
                description = "",
            ),
            last = Gig(
                id = GigId(eventimApollo.id, "https://www.eventimapollo.com/events/il-volo"),
                title = GigTitle("Il Volo"),
                date = LocalDate.of(2027, 11, 5),
                imageUrl = "https://aeg-media-assets.b-cdn.net/eventim/images/IL-VOLO-1080x1080-copy-1.jpg?width=768&height=768&focus_crop=1080,1080,0.5,0.5",
                description = "",
            ),
            urlPrefix = "https://www.eventimapollo.com/events/",
        )

        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(0)
        // the whole listing arrives in one page, so a size assertion is the only thing standing
        // between a month bar that starts navigating and a silently truncated listing
        expectThat(events.map { it.id.url }.distinct()).hasSize(83)
        // both date shapes are exercised by the two cards above: the first is a run of dates taking
        // its start, the last a single day. The listing runs from this August into late 2027.
        expectThat(events.map { it.date }.min()).isEqualTo(LocalDate.of(2026, 8, 16))
        expectThat(events.filter { it.date.year == 2027 }).hasSize(24)
    }

    @Test
    fun `extracts music events from OVO Arena's month calendar, leaving its other categories out`() {
        val events = assertScrapesGigs(
            source = OvoArenaGigsSource(cachedClient(), from = YearMonth.of(2026, 8)),
            size = 40,
            first = Gig(
                id = GigId(ovoArena.id, "https://www.ovoarena.co.uk/events/detail/stonebwoy#2026-08-15"),
                title = GigTitle("Stonebwoy"),
                date = LocalDate.of(2026, 8, 15),
                imageUrl = "https://www.ovoarena.co.uk/assets/img/STONEBWOY-BHIM-FEST-LONDON-1440x810-c5b626371c.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(ovoArena.id, "https://www.ovoarena.co.uk/events/detail/tash-sultana#2027-03-13"),
                title = GigTitle("RESCHEDULED DATE: Tash Sultana"),
                date = LocalDate.of(2027, 3, 13),
                imageUrl = "https://www.ovoarena.co.uk/assets/img/Tash_2027_-1440x810-1ccd5e6573.jpg",
                description = "",
            ),
            urlPrefix = "https://www.ovoarena.co.uk/events/detail/",
        )

        expectThat(events.count { it.imageUrl.isBlank() }).isEqualTo(0)
        expectThat(events.map { it.id.url }.distinct()).hasSize(40)
        // the eight months read held 59 events between them, so most of what the calendar returns is
        // dropped here - a filter that stopped filtering would show up as a much larger listing
        expectThat(events.filter { it.date.year == 2027 }).hasSize(6)
        // wrestling, comedy and a religious celebration all sit in those same months under another
        // category, and the drop-down's Music is what separates them
        expectThat(events.none { it.title.value.contains("Gladiators") || it.title.value.contains("Sunil Grover") }).isTrue()
    }

    // A month with nothing in it is not the end of the listing: read on 2026-08-17, this calendar had
    // no events at all in January 2027 and three in each of February and March.
    @Test
    fun `reads on past a month the OVO Arena calendar has nothing in`() {
        val events = OvoArenaGigsSource(cachedClient(), from = YearMonth.of(2026, 8)).latestGigs()

        expectThat(events.none { it.date.year == 2027 && it.date.monthValue == 1 }).isTrue()
        expectThat(events.filter { it.date.year == 2027 && it.date.monthValue == 3 }).hasSize(3)
    }

    // Each source parses its own event pages, so these go straight at that parsing - no listing page
    // to scrape first, and no http.
    private val noHttp: HttpHandler = { request -> error("unexpected http request: ${request.uri}") }

    private fun pageOf(html: String) = Jsoup.parse(html, "https://example.com/gig")

    // the age policy and the share links are verbatim from a real event page, where between them
    // they outran the gig's own blurb
    @Test
    fun `scopes The Underworld page text to the gig's own content, ignoring other-events widgets`() {
        val html = """
            <article class="event">
              <div class="content">
                <p>Doom metal night!</p>
                <p>This is a 14+ event. 14 and 15 year olds MUST be accompanied by an adult (18+) / All ticketholders under the age of 25 will be required to carry PHOTO ID</p>
              </div>
              <footer class="section"><ul class="event-share"><li><a>Share</a></li><li><a>Tweet</a></li></ul></footer>
            </article>
            <article class="list">
              <h3 class="list-header-title">KINGS OF THRASH</h3>
            </article>
        """.trimIndent()

        val pageText = TheUnderworldGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("KINGS OF THRASH")).isEqualTo(false)
        expectThat(pageText.contains("14+")).isEqualTo(false)
        expectThat(pageText.contains("PHOTO ID")).isEqualTo(false)
        expectThat(pageText.contains("Share")).isEqualTo(false)
        expectThat(pageText.contains("Tweet")).isEqualTo(false)
    }

    // the same policy paragraph, worded the other way round, on a gig with an 18+ door
    @Test
    fun `drops The Underworld age policy however it is worded`() {
        val html = """
            <article class="event">
              <div class="content">
                <p>Doom metal night!</p>
                <p>This event is an 18+ event</p>
              </div>
            </article>
        """.trimIndent()

        val pageText = TheUnderworldGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText).isEqualTo("Doom metal night!")
    }

    @Test
    // the attribute holds a JavaScript string literal rather than markup - angle brackets,
    // quotes and ampersands all arrive as unicode escapes - so a description read straight off it
    // is escapes and tags instead of the gig's own copy. The attribute here is verbatim from a
    // real listing, escapes and all, because a hand-written one without them tests nothing.
    fun `decodes New Cross Inn's client-rendered description into plain text`() {
        val html = """
            <p x-ref="desc" x-html="'\u003Ca href=\u0022https:\/\/www.facebook.com\/newcrosslive\u0022\u003E\u003Cstrong\u003ENew Cross Live\u003C\/strong\u003E\u003C\/a\u003E\u0026nbsp;presents\u003Cbr \/\u003E\r\n\u003Cbr \/\u003E\r\n\u003Cstrong\u003E\u003Ca href=\u0022https:\/\/www.facebook.com\/GhostUKTributeBand\u0022\u003EGhost UK\u003C\/a\u003E\u003C\/strong\u003E\u003Cbr \/\u003E\r\nThe Authentic UK Tribute to the band Ghost!\u003Cbr \/\u003E\r\n\u003Ca href=\u0022https:\/\/www.facebook.com\/GhostUKTributeBand\u0022\u003Ehttps:\/\/www.facebook.com\/GhostUKTributeBand\u003C\/a\u003E\u003Cbr \/\u003E\r\n\u003Cbr \/\u003E\r\nFriday 13th February 2027\u003Cbr \/\u003E\r\nNew Cross Inn\u003Cbr \/\u003E\r\nDoors 6pm\u003Cbr \/\u003E\r\nTickets \u0026pound;15 ADV STBF'"></p>
            <div>KINGS OF THRASH</div>
        """.trimIndent()

        val pageText = NewCrossInnGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("The Authentic UK Tribute to the band Ghost!")).isTrue()
        expectThat(pageText.contains("New Cross Live")).isTrue()
        // the entity decoded too, so a price reads as one rather than as an entity name
        expectThat(pageText.contains("Tickets £15 ADV STBF")).isTrue()
        expectThat(pageText.contains("u003C")).isEqualTo(false)
        expectThat(pageText.contains("<br")).isEqualTo(false)
        expectThat(pageText.contains("href")).isEqualTo(false)
        expectThat(pageText.contains("KINGS OF THRASH")).isEqualTo(false)
    }

    // two different kinds of boilerplate reach the whole page: the sitewide nav ("Summer Season",
    // "Food And Drink") outside #event_content, and a sidebar of generic quick-link buttons ("Buy
    // Tickets", "FAQs", ...) *inside* it - the second one only turned up against the real site,
    // after #event_content alone looked like enough of a fix
    @Test
    fun `scopes Alexandra Palace page text to the description and key-information accordion`() {
        val html = """
            <nav><li>Summer Season</li><li>Food And Drink</li></nav>
            <div id="event_content">
                <div class="event_sidebar"><ul class="event_buttons"><li>Buy Tickets</li><li>FAQs</li></ul></div>
                <div class="ap_text_block"><p>Doom metal night!</p></div>
                <div id="key-information"><h3>Key information</h3><p>Support from Kings of Thrash.</p></div>
            </div>
        """.trimIndent()

        val pageText = AlexandraPalaceGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Kings of Thrash")).isTrue()
        expectThat(pageText.contains("Summer Season")).isEqualTo(false)
        expectThat(pageText.contains("Buy Tickets")).isEqualTo(false)
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

    // Our Black Heart and The Dome share this same Squarespace "Events" template, and both delegate
    // to this scraper, so one fixture covers both. The meta block here is verbatim from a real
    // Black Heart page, where it outweighed the gig's own blurb on the shorter listings
    @Test
    fun `scopes Squarespace-venue page text to the content column, ignoring the event meta block`() {
        val html = """
            <nav><a>Home</a><a>About</a></nav>
            <article class="eventitem">
                <h1 class="eventitem-title">Doom Night</h1>
                <ul class="eventitem-meta event-meta">
                    <li class="eventitem-meta-date">Wednesday, August 12, 2026</li>
                    <li class="eventitem-meta-time">7:00 PM 11:00 PM <span>19:00 23:00</span></li>
                    <li class="eventitem-meta-address">The Black Heart 2 Greenland Place London, England, NW1 United Kingdom (map)</li>
                    <li class="eventitem-meta-export"><a>Google Calendar</a><a>ICS</a></li>
                </ul>
                <div class="eventitem-column-content"><p>Doom metal night!</p></div>
            </article>
            <footer><a>Instagram</a><a>Privacy Policy</a></footer>
        """.trimIndent()

        val source = SquarespaceEventsGigsSource(noHttp, url = "https://example.com/events", venue = ourBlackHeart)
        val pageText = source.eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Greenland Place")).isEqualTo(false)
        expectThat(pageText.contains("19:00")).isEqualTo(false)
        expectThat(pageText.contains("Google Calendar")).isEqualTo(false)
        expectThat(pageText.contains("About")).isEqualTo(false)
        expectThat(pageText.contains("Instagram")).isEqualTo(false)
    }

    // dice.fm venues (Blondies Brewery Taproom, Blondies Bar, Helgi's, Barfly Camden) render almost nothing
    // server-side to select from - the real description is nested two JSON parses deep inside
    // __NEXT_DATA__ (itself containing a JSON-encoded string), alongside plenty of sitewide data
    // (i18n strings, nav) this fixture only trims down, not invents
    @Test
    fun `scopes dice-fm page text to the event's own about-description, ignoring the surrounding JSON`() {
        val html = """
            <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"otherStuff":"ignore me","initialState":"{\"event\":{\"event\":{\"about\":{\"description\":\"Doom metal night!\"}}}}"}}}
            </script>
        """.trimIndent()

        val pageText = diceEventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("ignore me")).isEqualTo(false)
    }

    // The Garage and The Grace (both DHP Family, sharing DhpVenueGigsSource) share this same
    // WordPress theme - the obvious ".single-article" also matches its own outer wrapper div,
    // which would double every word of text, so this is scoped to the more specific inner class.
    // The meta bar, list and CTA below are verbatim from a real listing, which they made up most of
    @Test
    fun `scopes DHP-venue page text to the content block, dropping the meta bar and the CTA`() {
        val html = """
            <nav><a>Home</a><a>News</a></nav>
            <div class="section single-article">
                <section class="single-article single-article--contains-list">
                    <div class="single-article__title-bar"><h1>Doom Night</h1> The Garage, London</div>
                    <div class="single-article__meta-bar"><a>BUY TICKETS</a> Sat 15th August 2026 7:00 pm £20</div>
                    <article class="single-article__content">
                        <p>Doom metal night!</p>
                        <p><em>For more events, check out what's on here.</em></p>
                    </article>
                    <ul class="single-article__list"><li>Date: Sat 15th August 2026</li><li>Doors Open: 7:00 pm</li><li>On Sale: Tickets Open</li><li>Price: £20</li></ul>
                </section>
            </div>
            <section><h2>ON SPOTIFY</h2></section>
        """.trimIndent()

        val source = DhpVenueGigsSource(noHttp, url = "https://example.com/live/", venue = theGarage)
        val pageText = source.eventPageContent(pageOf(html))!!

        expectThat(pageText).isEqualTo("Doom metal night!")
    }

    // the policy paragraph and the meta line are verbatim from a real listing, where together with
    // the repeated title they ran longer than the gig's own copy
    @Test
    fun `scopes Electric Ballroom page text to the content column, dropping the age policy`() {
        val html = """
            <header><nav><a>Whats On</a></nav><span class="header-address">184 CAMDEN HIGH STREET, CAMDEN TOWN, LONDON, NW1 8QP</span></header>
            <article>
                <h1>Doom Night</h1>
                <div class="cf"><a>← Back</a>
                    <div class="article-content">
                        <p>Doom metal night!</p>
                        <p>Please note this show is 14+ (under 16s must be accompanied by an 18+ adult). Valid physical photo ID is required for entry!</p>
                    </div>
                    <div class="event-meta">7.00PM | £25</div>
                    <div class="buy-share-event">Buy Tickets</div>
                </div>
            </article>
            <footer><a>Facebook</a></footer>
        """.trimIndent()

        val pageText = ElectricBallroomGigsSource(noHttp, year = 2026).eventPageContent(pageOf(html))!!

        expectThat(pageText).isEqualTo("Doom metal night!")
    }

    // the same policy, worded two other ways the venue also uses
    @Test
    fun `drops the Electric Ballroom age policy however it is worded`() {
        val phrasings = listOf(
            "Strictly 18+ / physical photo ID required at entry.",
            "Please note this show is 14+ (under 16s must be accompanied by an 18+ adult / Proof of age is required at entry.)",
        )

        phrasings.forEach { policy ->
            val html = """<div class="article-content"><p>Doom metal night!</p><p>$policy</p></div>"""

            val pageText = ElectricBallroomGigsSource(noHttp, year = 2026).eventPageContent(pageOf(html))!!

            expectThat(pageText).isEqualTo("Doom metal night!")
        }
    }

    @Test
    fun `scopes Dingwalls page text to the Elementor single-page template`() {
        val html = """
            <nav><a>Home</a></nav>
            <div data-elementor-type="single-page" class="elementor elementor-750 elementor-location-single">
                <h1>Doom Night</h1>
                <div class="elementor-widget-theme-post-content"><p>Doom metal night!</p></div>
            </div>
            <footer><a>Instagram</a></footer>
        """.trimIndent()

        val pageText = DingwallsGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Home")).isEqualTo(false)
    }

    // ".event-about" holds the real description alongside a "Related events" carousel and a booking
    // card *nested inside it*, not as sibling sections - that's why the exclusions are by class, not
    // by boundary. The card's 142 words of booking schedule, digital-ticket notice and
    // restoration-levy small print are identical on every venue-run page
    @Test
    fun `scopes Roundhouse page text to the event content, excluding the nested related-events and booking blocks`() {
        val html = """
            <div class="event-hero__heading-wrapper"><h1>Doom Night</h1></div>
            <section class="event-about">
                <div class="layout-block layout-block--text-block-with-title"><p>Doom metal night!</p></div>
                <div class="layout-block layout-block--event-listing-card"><p>This event is digitally ticketed.</p></div>
                <div class="layout-block layout-block--related-events-list"><h3>Related events</h3><p>Other Gig</p></div>
            </section>
        """.trimIndent()

        val pageText = RoundhouseGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Other Gig")).isEqualTo(false)
        expectThat(pageText.contains("digitally ticketed")).isEqualTo(false)
    }

    // a promoter-run show puts its copy straight into ".event-about" with none of the layout blocks
    // the venue's own listings use, and has no hero heading wrapper at all
    @Test
    fun `takes Roundhouse page text from a promoter-run page that has no layout blocks`() {
        val html = """
            <section class="event-about">
                <div class="layout layout--main"><p>Doom metal night!</p></div>
            </section>
        """.trimIndent()

        expectThat(RoundhouseGigsSource(noHttp).eventPageContent(pageOf(html))).isEqualTo("Doom metal night!")
    }

    // the sections after "Book For A Pre-Show Dinner" are verbatim from a real listing, where they
    // run to some 1,850 characters identical on every page
    @Test
    fun `scopes Union Chapel page text to the gig's own copy and the event-information sidebar`() {
        val html = """
            <nav><a>Whats On</a></nav>
            <div id="content">
                <article class="pt-4">
                    <h1>Doom Night</h1>
                    <h4>For tickets to this event click BOOK NOW button above</h4>
                    <h4>Scroll down for info on reserving a pre-show meal with Margins Cafe.</h4>
                    <p>Doom metal night!</p>
                    <h4>Book For A Pre-Show Dinner</h4>
                    <p>The Margins Cafe serves delicious, freshly prepared food at gigs and events.</p>
                    <p>More Information:</p>
                    <p>Alcohol consumption will be limited to the bar area only.</p>
                </article>
            </div>
            <aside><div class="sidebar p-3"><h6>WHEN</h6><p>7pm</p></div></aside>
            <footer class="pt-4"><a>Instagram</a></footer>
        """.trimIndent()

        val pageText = UnionChapelGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("7pm")).isTrue()
        expectThat(pageText.contains("Margins Cafe")).isEqualTo(false)
        expectThat(pageText.contains("Alcohol consumption")).isEqualTo(false)
        expectThat(pageText.contains("BOOK NOW")).isEqualTo(false)
        expectThat(pageText.contains("Whats On")).isEqualTo(false)
        expectThat(pageText.contains("Instagram")).isEqualTo(false)
    }

    @Test
    fun `extracts gig events from Electric Brixton's events page, following pagination`() {
        assertScrapesGigs(
            source = ElectricBrixtonGigsSource(cachedClient()),
            size = 54,
            first = Gig(
                id = GigId(electricBrixton.id, "https://www.electricbrixton.uk.com/events/bacchanal-friday-4/"),
                title = GigTitle("Bacchanal Friday"),
                date = LocalDate.of(2026, 8, 28),
                imageUrl = "https://e2h4j4t3.rocketcdn.me/wp-content/uploads/2025/01/Busspepper-1200.jpg",
                description = "",
            ),
            last = Gig(
                id = GigId(electricBrixton.id, "https://www.electricbrixton.uk.com/events/elder/"),
                title = GigTitle("Elder"),
                date = LocalDate.of(2027, 2, 27),
                imageUrl = "https://e2h4j4t3.rocketcdn.me/wp-content/uploads/2026/06/Elder-1200.jpg",
                description = "",
            ),
            urlPrefix = "https://www.electricbrixton.uk.com/events/",
        )
    }

    // the door/price/age furniture is verbatim from a real listing, and repeats on every one of them
    @Test
    fun `scopes Electric Brixton page text to the gig's own copy`() {
        val html = """
            <nav><a>What's On</a></nav>
            <div class="split-top event-info">
                <h4>19:00 - 23:00</h4><h4>£50 + BF</h4>
            </div>
            <div class="split-bottom event-desc">
                <p>Doom Promotions Presents</p><p>Doom Night</p><p>18+ (Physical photo ID required)</p>
            </div>
            <div class="event-item event-context">
                <p>Doom metal night, with Kings Of Thrash in support.</p>
            </div>
            <div class="uabb-subscribe-form"><label>Sign up to our mailing list</label></div>
        """.trimIndent()

        val pageText = ElectricBrixtonGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night, with Kings Of Thrash in support.")).isTrue()
        expectThat(pageText.contains("Physical photo ID required")).isEqualTo(false)
        expectThat(pageText.contains("£50 + BF")).isEqualTo(false)
        expectThat(pageText.contains("mailing list")).isEqualTo(false)
        expectThat(pageText.contains("What's On")).isEqualTo(false)
    }

    // the ticketing and access blocks are verbatim from a real listing, where they ran to 414 chars
    // against the gig's own few hundred
    @Test
    fun `scopes Scala page text to the lineup and the About section`() {
        val html = """
            <nav><a>Home</a></nav>
            <div id="post-1" class="post-1 event type-event event-post">
                <h1 class="entry-title">Doom Night</h1>
                <div class="entry-content">
                    <div class="tb-event-headerbox">
                        <div class="tb-event-headerbox-titlebox">
                            <p class="event-date">Wednesday 19th August 2026</p>
                            <p class="promoter">Doom Promotions presents </p>
                            <h1 class="event-title">Doom Night</h1>
                            <h2 class="event-subtitle">Plus Kings Of Thrash</h2>
                            <p class="event-time">7:30 pm until 10:15 pm</p>
                            <div class="left-morebox"><a href="https://link.dice.fm/x">Buy tickets</a></div>
                            <div class="right-morebox"><a href="#tickets">Info</a></div>
                        </div>
                    </div>
                    <div>Tickets Price: From £36.47 <p class="guide-to">Read our guide to buying and using tickets.</p></div>
                    <div>Admission <p class="event-time">Doors open at 7:30 PM</p><p class="age-restrictions">Age: You must be 18 years of age or more to attend this event (no exceptions). | Photo ID – We require original physical (non-digital) photo ID and use ID scanning.</p></div>
                    <h3>About Doom Night</h3>
                    <p>Doom metal night!</p>
                    <p class="add-calendar">Add to iCal | Add to Google calendar</p>
                </div>
            </div>
            <div id="sidebar"><ul><li>Other Gig</li></ul></div>
        """.trimIndent()

        val pageText = ScalaGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Kings Of Thrash")).isTrue()
        expectThat(pageText.contains("Doom Promotions presents")).isTrue()
        // the gig's date is a field of its own, so as prose it only reads as a second one
        expectThat(pageText.contains("Wednesday 19th August 2026")).isEqualTo(false)
        expectThat(pageText.contains("7:30 pm until")).isEqualTo(false)
        expectThat(pageText.contains("Buy tickets")).isEqualTo(false)
        expectThat(pageText.contains("Photo ID")).isEqualTo(false)
        expectThat(pageText.contains("Read our guide")).isEqualTo(false)
        expectThat(pageText.contains("Add to iCal")).isEqualTo(false)
        expectThat(pageText.contains("Other Gig")).isEqualTo(false)
    }

    @Test
    fun `scopes Paper Dress Vintage page text to the event content, excluding nav and footer`() {
        val html = """
            <nav><a>Home</a><a>Book a table</a></nav>
            <div class="event__content"><p>Doom metal night!</p></div>
            <footer><a>Contact</a><a>Opening hours</a></footer>
        """.trimIndent()

        val pageText = PaperDressVintageGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Opening hours")).isEqualTo(false)
    }

    // the terms and levy paragraphs are verbatim from a real listing, where they close every one -
    // on the thinnest they were the whole description. The terms one is typed with a leading
    // asterisk on most listings and without it on others, so this has one of each.
    @Test
    fun `scopes Islington Assembly Hall page text to the copy, dropping the terms and levy paragraphs`() {
        val html = """
            <nav><a>What's On</a><a>Hire the Hall</a></nav>
            <ul class="event__details__list"><li>Date 21/08/2026</li><li>Total price, inc booking fee £27.78</li></ul>
            <div class="event__description body--wysiwyg">
                <p>Doom metal night, with Kings Of Thrash in support.</p>
                <p>By purchasing a ticket to this event you are agreeing to adhere to Islington Assembly Hall&#8217;s terms and conditions: https://islingtonassemblyhall.co.uk/customer-terms-conditions-2022/</p>
                <p>*All tickets to shows at Islington Assembly Hall are subject to a Venue Levy of £1.50 + VAT. As a Grade II listed building, this levy will be reinvested into Islington Assembly Hall and its services, meaning the customer experience can continue to be enhanced.*</p>
                <p>Presented by Doom Promotions.</p>
                <p>This is a 16+ event.</p>
            </div>
            <div class="entry__related"><li class="event__item"><a class="event__item__title">Some Other Gig</a></li></div>
        """.trimIndent()

        val pageText = IslingtonAssemblyHallGigsSource(noHttp, year = 2026).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night, with Kings Of Thrash in support.")).isTrue()
        // who booked the show is the one thing some of these listings say beyond the boilerplate
        expectThat(pageText.contains("Presented by Doom Promotions.")).isTrue()
        expectThat(pageText.contains("terms and conditions")).isEqualTo(false)
        expectThat(pageText.contains("Venue Levy")).isEqualTo(false)
        expectThat(pageText.contains("16+")).isEqualTo(false)
        expectThat(pageText.contains("£27.78")).isEqualTo(false)
        expectThat(pageText.contains("Some Other Gig")).isEqualTo(false)
        expectThat(pageText.contains("Hire the Hall")).isEqualTo(false)
    }

    // the entry, ticket and sharing furniture is verbatim from a real listing, where between them
    // they ran longer than the gig's own copy
    @Test
    fun `scopes Windmill Brixton page text to the promoter's own copy`() {
        val html = """
            <nav><a>Listings</a><a>Visitor Info</a></nav>
            <article class="Event EventDetail">
                <p class="EventDetailPromoterPresents">The Windmill presents:</p>
                <h1 class="EventDetailTitle-title">Doom Night</h1>
                <div class="EventDetailEntry">
                    <span class="EventDetailPrice-price">£5</span>
                    <p class="EventDetailEntry-requirements">Entry Requirements: 18+</p>
                </div>
                <div class="EventTickets">
                    <div class="EventTicket"><span>General Admission (e-ticket)</span>
                        <span class="Price">£5.00</span><span class="ServiceCharge">+ £1 s/c</span>
                    </div>
                </div>
                <div class="EventDetailDescription">
                    <p>Doom metal night, with <a href="https://instagram.com/x">Kings Of Thrash</a> in support.</p>
                </div>
                <div class="EventDetailSharing"><a><span>Share</span></a><a><span>Tweet</span></a></div>
            </article>
            <div class="MailingList"><p>By signing up you agree to receive news and offers from Windmill Brixton.</p></div>
        """.trimIndent()

        val pageText = WindmillBrixtonGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night, with Kings Of Thrash in support.")).isTrue()
        expectThat(pageText.contains("Entry Requirements")).isEqualTo(false)
        expectThat(pageText.contains("General Admission")).isEqualTo(false)
        expectThat(pageText.contains("s/c")).isEqualTo(false)
        expectThat(pageText.contains("Tweet")).isEqualTo(false)
        expectThat(pageText.contains("Visitor Info")).isEqualTo(false)
        expectThat(pageText.contains("news and offers")).isEqualTo(false)
    }

    // the age policy, the AXS ticket-transfer notice and the travel warning about the stadium next
    // door are all longer than some gigs' own copy, and none of them is about the act
    @Test
    fun `takes OVO Arena's gig copy without the venue's ticketing and travel notices`() {
        val html = """
            <div class="event_detail one_sidebar_right has_branding">
                <div class="ticketcontent">
                    <p>Find tickets Buy premium Date 04 Sep / 2026 Doors 18:00 Ticket Information</p>
                    <p>Age Restriction Standing: strictly 14+, with 14-15 year olds to be accompanied by an adult (16+)</p>
                    <p>For this show, if you&rsquo;ve purchased your tickets via AXS, you&rsquo;ll need to display your ticket on your phone.</p>
                    <p>Please note, there is a Bon Jovi concert taking place next door at the stadium on 4th September 2026.</p>
                    <p>There will be road closures in place around the area from early on.</p>
                </div>
                <div class="event_description expandable" data-options="event_detail" tabindex="0">
                    <p>The Neighbourhood is a California-based alternative rock band comprised of Jesse Rutherford and Zach Abels.</p>
                </div>
            </div>
        """.trimIndent()

        val pageText = OvoArenaGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("California-based alternative rock band")).isTrue()
        expectThat(pageText.contains("Age Restriction")).isEqualTo(false)
        expectThat(pageText.contains("AXS")).isEqualTo(false)
        expectThat(pageText.contains("road closures")).isEqualTo(false)
        expectThat(pageText.contains("Bon Jovi")).isEqualTo(false)
        expectThat(pageText.contains("Doors 18:00")).isEqualTo(false)
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

    // Extraction that matches nothing is what a changed site looks like, and it reaches the gig as a
    // blank description rather than as a failed scrape.
    @Test
    fun `fails rather than building a gig whose description its page never gave`() {
        val changedMarkup = "<div>page markup changed, no article.event here</div>"
        val servingChangedMarkup: HttpHandler = { Response(OK).body(changedMarkup) }
        val source = TheUnderworldGigsSource(noHttp)

        expectThat(source.eventPageContent(pageOf(changedMarkup))).isEqualTo(null)
        // markup that no longer matches, and a page that won't fetch at all, both fail outright
        assertFailsWith<IllegalStateException> {
            fetchDescription(servingChangedMarkup, "https://example.com/gig", source::eventPageContent)
        }
        assertFailsWith<IllegalStateException> {
            fetchDescription(noHttp, "https://example.com/gig", source::eventPageContent)
        }
        // "" is only ever a page that was read and had nothing to say about its gig
        expectThat(fetchDescription(servingChangedMarkup, "https://example.com/gig") { "" }).isEqualTo("")
    }
}
