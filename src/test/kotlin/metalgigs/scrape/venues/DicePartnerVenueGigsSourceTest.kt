package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test

// Every Dice venue reads the same partner API, and each is given a client that hands back the
// redirect dice.fm answers a listed perm_name with rather than following it: that redirect is where
// the gig lives, so a following client would identify every gig here by a url dice.fm doesn't serve.
// Why this reading surface and paging: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
// Why a gig's url is its identity: docs/adr/0005-a-gig-is-identified-by-the-url-it-lives-at.md
class DicePartnerVenueGigsSourceTest {

    @Test
    fun `extracts Blackhorse Road gigs from the Dice partner API`() {
        assertScrapesGigs(
            source = SignatureBrewBlackhorseRoadGigsSource(cachedClient(followRedirects = false)),
            size = 23,
            first = Gig(
                GigId(signatureBrewBlackhorseRoad.id, GigUrl("https://dice.fm/event/ry87gv-disco-2000-summer-yard-party-london-23rd-aug-signature-brew-blackhorse-road-london-tickets")),
                GigTitle("Disco 2000 Summer Yard Party | London"),
                GigDate(2026, 8, 23),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-06-18/c2d85f59-1c17-4a96-8291-77270ebeba4b.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(signatureBrewBlackhorseRoad.id, GigUrl("https://dice.fm/event/nv5589-dig-it-up-by-the-allergies-london-17th-apr-signature-brew-blackhorse-road-london-tickets")),
                GigTitle("Dig It Up by The Allergies | London"),
                GigDate(2027, 4, 17),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-07-10/63524421-198d-4192-bbd4-44bd063bf8e5.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )
    }

    @Test
    fun `extracts Haggerston gigs from the Dice partner API`() {
        val gigs = assertScrapesGigs(
            source = SignatureBrewHaggerstonGigsSource(cachedClient(followRedirects = false)),
            size = 38,
            first = Gig(
                GigId(signatureBrewHaggerston.id, GigUrl("https://dice.fm/event/xeaqvm-popscene-the-ultimate-blur-tribute-london-22nd-aug-signature-brew-haggerston-london-tickets")),
                GigTitle("Popscene - The Ultimate Blur Tribute | London"),
                GigDate(2026, 8, 22),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-03-23/91f6dfdd-9cae-4e5e-ae79-c5ff6ed2041b.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(signatureBrewHaggerston.id, GigUrl("https://dice.fm/event/l8kmxb-duck-dive-festival-2027-london-26th-feb-signature-brew-haggerston-london-tickets")),
                GigTitle("DUCK & DIVE FESTIVAL 2027 | LONDON"),
                GigDate(2027, 2, 26),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-07-15/bab2924f-aa0a-4549-81e9-818da1a845b1.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )

        // the venue lists Comedy Incorporated's stand-up night among its gigs, seven of them in this
        // listing, two of which are subtitled rather than titled with the name alone
        expectThat(gigs.none { it.title.value.contains("Comedy Incorporated", ignoreCase = true) }).isTrue()
    }

    @Test
    fun `extracts gig events from 229's Dice partner-widget API`() {
        val events = assertScrapesGigs(
            source = TwoTwoNineGigsSource(cachedClient(followRedirects = false)),
            size = 79,
            first = Gig(
                GigId(twoTwoNine.id, GigUrl("https://dice.fm/event/6d8pyq-2baba-experience-london-21st-aug-229-london-tickets")),
                GigTitle("2BABA Experience! London"),
                GigDate(2026, 8, 21),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-06-18/2cc2453a-ab2c-4af7-8ef0-4cee65265d5d.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(twoTwoNine.id, GigUrl("https://dice.fm/event/av533q-leo-kottke-9th-jun-229-london-tickets")),
                GigTitle("Leo Kottke"),
                GigDate(2027, 6, 9),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-06-01/e83611c7-842b-4a07-ae83-b29386d816dc.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )

        // the api names each act apart from the event's name, which here is the headliner alone
        expectThat(events.single { it.title == GigTitle("NITE") }.lineup).isEqualTo(Lineup(listOf("Nite", "A Thousand Mad Things", "Das Fluff")))
        expectThat(events.count { it.lineup != Lineup.none }).isEqualTo(66)
    }

    @Test
    fun `extracts gig events from Blondies Brewery Taproom's listing`() {
        val events = assertScrapesGigs(
            source = BlondiesBreweryTaproomGigsSource(cachedClient(followRedirects = false)),
            size = 17,
            first = Gig(
                GigId(blondiesBreweryTaproom.id, GigUrl("https://dice.fm/event/g7o5aa-el-altar-del-holocausto-26th-nov-blondies-brewery-london-tickets")),
                GigTitle("El Altar del Holocausto - CANCELLED"),
                GigDate(2026, 11, 26),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-10-31/da25b72c-1ea4-4314-a141-3578091b9e53.jpg"),
                GigDescription(""),
                Lineup.none,
                GigStatus.Cancelled,
            ),
            last = Gig(
                GigId(blondiesBreweryTaproom.id, GigUrl("https://dice.fm/event/693n7b-savanah-sleeping-mountain-at-blondies-taproom-7th-may-blondies-brewery-london-tickets")),
                GigTitle("Savanah | Sleeping Mountain at Blondies Taproom"),
                GigDate(2027, 5, 7),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-11-17/f023961a-ef77-463f-be44-4c94c0d3d2b4.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )

        // a cancelled gig stays on the listing looking like any other - Dice's own event page marks
        // it with a "Cancelled" button where the ticket one goes, and the API with its status
        expectThat(events.map { it.title }).contains(GigTitle("El Altar del Holocausto - CANCELLED"))
        expectThat(events.filter { it.status == GigStatus.Cancelled }.map { it.title })
            .isEqualTo(events.map { it.title }.filter { it.value.endsWith(" - CANCELLED") })

        // the promoter made that gig twice, two events under one perm_name, and dice.fm redirects the
        // perm_name to the first of them - so the second is found under its own hash instead
        expectThat(events.filter { it.status == GigStatus.Cancelled }.map { it.id.url }).isEqualTo(
            listOf(
                GigUrl("https://dice.fm/event/g7o5aa-el-altar-del-holocausto-26th-nov-blondies-brewery-london-tickets"),
                GigUrl("https://dice.fm/event/yy26mr-el-altar-del-holocausto-26th-nov-blondies-brewery-london-tickets"),
            )
        )
    }

    @Test
    fun `extracts gig events from Blondies Bar's listing`() {
        assertScrapesGigs(
            source = BlondiesBarGigsSource(cachedClient(followRedirects = false)),
            size = 26,
            first = Gig(
                GigId(blondiesBar.id, GigUrl("https://dice.fm/event/nv678l-live-n-loud-free-karaoke-sundays-sing-wtf-you-want-23rd-aug-blondies-london-tickets")),
                GigTitle("LIVE 'N' LOUD / Free Karaoke Sundays - SING WTF YOU WANT"),
                GigDate(2026, 8, 23),
                PosterUrl("https://dice-media.imgix.net/attachments/2025-07-23/754dcb90-3fc6-48d1-a030-a8860183e565.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(blondiesBar.id, GigUrl("https://dice.fm/event/bboxdm-1986-support-5th-dec-blondies-london-tickets")),
                GigTitle("1986 + Support"),
                GigDate(2026, 12, 5),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-04-27/4c005268-bc5b-43c7-a69f-8117623d0232.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )
    }

    @Test
    fun `extracts gig events from Helgi's listing`() {
        assertScrapesGigs(
            source = HelgisGigsSource(cachedClient(followRedirects = false)),
            size = 14,
            first = Gig(
                GigId(helgis.id, GigUrl("https://dice.fm/event/53mv5k-fullmne-stone-barrow-marks-of-satan-22nd-aug-helgis-london-tickets")),
                GigTitle("Fullmåne + Stone Barrow + Marks of Satan"),
                GigDate(2026, 8, 22),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-07-27/4292fd18-8de4-43e6-9701-4ed92e91b838.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(helgis.id, GigUrl("https://dice.fm/event/xedvra-holocaust-hyena-14th-nov-helgis-london-tickets")),
                GigTitle("HOLOCAUST + HYENA"),
                GigDate(2026, 11, 14),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-04-07/cdca232f-2df2-41a6-a2b1-cdaa5c827aa3.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )
    }

    @Test
    fun `extracts gig events from Barfly's listing`() {
        val gigs = assertScrapesGigs(
            source = BarflyGigsSource(cachedClient(followRedirects = false)),
            size = 26,
            first = Gig(
                GigId(barfly.id, GigUrl("https://dice.fm/event/3kqkwp-sleazebox-indie-disco-at-camden-barfly-free-entry-27th-nov-barfly-camden-london-tickets")),
                GigTitle("SleazeBox indie disco at Camden Barfly (free entry)"),
                GigDate(2026, 11, 27),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-11-02/036de721-be3c-40f8-affa-240e57a67880.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(barfly.id, GigUrl("https://dice.fm/event/2wakgo-lost-alone-20th-anniversary-show-26th-mar-barfly-camden-london-tickets")),
                GigTitle("LOST ALONE - 20th Anniversary Show"),
                GigDate(2027, 3, 26),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-09-14/e2b19430-2245-4576-87df-98754bcf2d75.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )

        // the api lists a 27th event with no image of any size, which is named as ok to lose
        expectThat(gigs.none { it.title == GigTitle("Mock Tudors") }).isTrue()
    }

    @Test
    fun `extracts gig events from Strongroom's listing`() {
        val gigs = assertScrapesGigs(
            source = StrongroomGigsSource(cachedClient(followRedirects = false)),
            size = 19,
            first = Gig(
                GigId(strongroom.id, GigUrl("https://dice.fm/event/6do2k8-kasador-19th-nov-strongroom-london-tickets")),
                GigTitle("Kasador"),
                GigDate(2026, 11, 19),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-08-11/55e03c87-57ed-4539-a49f-04f3261a05f9.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(strongroom.id, GigUrl("https://dice.fm/event/pgoo28-mothermary-9th-feb-strongroom-london-tickets")),
                GigTitle("Mothermary"),
                GigDate(2027, 2, 9),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-10-29/83fb8403-c13a-474e-b1a4-d50288c256bd.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )

        // dice.fm answered this one's bare perm_name with a 404 rather than a redirect, as it did
        // four more in this recording, while serving its page under the event's hash like any other
        expectThat(gigs.single { it.title == GigTitle("Twin Skeletons") }.id.url)
            .isEqualTo(GigUrl("https://dice.fm/event/ryrxaw-twin-skeletons-28th-nov-strongroom-london-tickets"))

        // the bar lists its film screenings, quiz and stand-up among its gigs, four in this listing
        expectThat(gigs.none { it.title.value.contains(Regex("Open Screen|Quiz|Comedy")) }).isTrue()

        // the api hands this one's copy over as the promoter pasted it, "UK &amp; Europe"
        expectThat(gigs.single { it.title == GigTitle("Sunday,June") }.description.value).contains("followed by a UK & Europe tour")
    }
}
