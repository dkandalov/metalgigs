package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.contains
import kotlin.test.Test

// Every Dice venue reads the same partner API, and each is given a client that hands back the
// redirect dice.fm answers a listed perm_name with rather than following it: that redirect is where
// the gig lives, so a following client would identify every gig here by a url dice.fm doesn't serve.
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
            ),
            last = Gig(
                GigId(signatureBrewBlackhorseRoad.id, GigUrl("https://dice.fm/event/nv5589-dig-it-up-by-the-allergies-london-17th-apr-signature-brew-blackhorse-road-london-tickets")),
                GigTitle("Dig It Up by The Allergies | London"),
                GigDate(2027, 4, 17),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-07-10/63524421-198d-4192-bbd4-44bd063bf8e5.jpg"),
                GigDescription(""),
            ),
        )
    }

    @Test
    fun `extracts Haggerston gigs from the Dice partner API`() {
        assertScrapesGigs(
            source = SignatureBrewHaggerstonGigsSource(cachedClient(followRedirects = false)),
            size = 45,
            first = Gig(
                GigId(signatureBrewHaggerston.id, GigUrl("https://dice.fm/event/xeaqvm-popscene-the-ultimate-blur-tribute-london-22nd-aug-signature-brew-haggerston-london-tickets")),
                GigTitle("Popscene - The Ultimate Blur Tribute | London"),
                GigDate(2026, 8, 22),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-03-23/91f6dfdd-9cae-4e5e-ae79-c5ff6ed2041b.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(signatureBrewHaggerston.id, GigUrl("https://dice.fm/event/l8kmxb-duck-dive-festival-2027-london-26th-feb-signature-brew-haggerston-london-tickets")),
                GigTitle("DUCK & DIVE FESTIVAL 2027 | LONDON"),
                GigDate(2027, 2, 26),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-07-15/bab2924f-aa0a-4549-81e9-818da1a845b1.jpg"),
                GigDescription(""),
            ),
        )
    }

    @Test
    fun `extracts gig events from 229's Dice partner-widget API`() {
        assertScrapesGigs(
            source = TwoTwoNineGigsSource(cachedClient(followRedirects = false)),
            size = 79,
            first = Gig(
                GigId(twoTwoNine.id, GigUrl("https://dice.fm/event/6d8pyq-2baba-experience-london-21st-aug-229-london-tickets")),
                GigTitle("2BABA Experience! London"),
                GigDate(2026, 8, 21),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-06-18/2cc2453a-ab2c-4af7-8ef0-4cee65265d5d.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(twoTwoNine.id, GigUrl("https://dice.fm/event/av533q-leo-kottke-9th-jun-229-london-tickets")),
                GigTitle("Leo Kottke"),
                GigDate(2027, 6, 9),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-06-01/e83611c7-842b-4a07-ae83-b29386d816dc.jpg"),
                GigDescription(""),
            ),
        )
    }

    @Test
    fun `extracts gig events from Blondies Brewery Taproom's listing`() {
        val events = assertScrapesGigs(
            source = BlondiesBreweryTaproomGigsSource(cachedClient(followRedirects = false)),
            size = 13,
            first = Gig(
                GigId(blondiesBreweryTaproom.id, GigUrl("https://dice.fm/event/avxxrq-karaoke-thursdays-club-grazia-special-guest-host-27th-aug-blondies-brewery-london-tickets")),
                GigTitle("Karaoke Thursdays / CLUB GRAZIA SPECIAL GUEST HOST"),
                GigDate(2026, 8, 27),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-03-02/242a563f-6cbc-42dd-8eb8-cc2cfa1bdb78.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(blondiesBreweryTaproom.id, GigUrl("https://dice.fm/event/8eq9dw-forlorn-birdwitch-27th-nov-blondies-brewery-london-tickets")),
                GigTitle("FORLORN / BIRDWITCH"),
                GigDate(2026, 11, 27),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-07-13/d2e1f34c-9f57-4a47-811c-5e6d4efbc40a.jpg"),
                GigDescription(""),
            ),
        )

        // a cancelled gig stays on the listing looking like any other - Dice's own event page marks
        // it with a "Cancelled" button where the ticket one goes, and the API with its status
        expectThat(events.map { it.title }).contains(GigTitle("BLONDIES 11TH BIRTHDAY PARTY - CANCELLED"))
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
            ),
            last = Gig(
                GigId(blondiesBar.id, GigUrl("https://dice.fm/event/bboxdm-1986-support-5th-dec-blondies-london-tickets")),
                GigTitle("1986 + Support"),
                GigDate(2026, 12, 5),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-04-27/4c005268-bc5b-43c7-a69f-8117623d0232.jpg"),
                GigDescription(""),
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
            ),
            last = Gig(
                GigId(helgis.id, GigUrl("https://dice.fm/event/xedvra-holocaust-hyena-14th-nov-helgis-london-tickets")),
                GigTitle("HOLOCAUST + HYENA"),
                GigDate(2026, 11, 14),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-04-07/cdca232f-2df2-41a6-a2b1-cdaa5c827aa3.jpg"),
                GigDescription(""),
            ),
        )
    }

    @Test
    fun `extracts gig events from Barfly's listing`() {
        assertScrapesGigs(
            source = BarflyGigsSource(cachedClient(followRedirects = false)),
            size = 27,
            first = Gig(
                GigId(barfly.id, GigUrl("https://dice.fm/event/wwddqr-archangels-live-in-london-21st-aug-barfly-camden-london-tickets")),
                GigTitle("Archangels live in London"),
                GigDate(2026, 8, 21),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-05-01/76f984f2-6a7d-46e9-b753-a25c7183f4cd.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(barfly.id, GigUrl("https://dice.fm/event/k6lw79-forever-never-13th-feb-barfly-camden-london-tickets")),
                GigTitle("Forever Never"),
                GigDate(2027, 2, 13),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-06-16/6f0ca902-24a7-4421-a288-f70e55030959.jpg"),
                GigDescription(""),
            ),
        )
    }
}
