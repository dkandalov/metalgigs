package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import kotlin.test.Test

class DicePartnerVenueGigsSourceTest {

    @Test
    fun `extracts Blackhorse Road gigs from the Dice partner API`() {
        assertScrapesGigs(
            source = SignatureBrewBlackhorseRoadGigsSource(cachedClient()),
            size = 23,
            first = Gig(
                GigId(signatureBrewBlackhorseRoad.id, GigUrl("https://dice.fm/event/disco-2000-summer-yard-party-london-23rd-aug-signature-brew-blackhorse-road-london-tickets")),
                GigTitle("Disco 2000 Summer Yard Party | London"),
                GigDate(2026, 8, 23),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-06-18/c2d85f59-1c17-4a96-8291-77270ebeba4b.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(signatureBrewBlackhorseRoad.id, GigUrl("https://dice.fm/event/dig-it-up-by-the-allergies-london-17th-apr-signature-brew-blackhorse-road-london-tickets")),
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
            source = SignatureBrewHaggerstonGigsSource(cachedClient()),
            size = 46,
            first = Gig(
                GigId(signatureBrewHaggerston.id, GigUrl("https://dice.fm/event/papangu-zeta-meiotempo-london-18th-aug-signature-brew-haggerston-london-tickets")),
                GigTitle("Papangu + Zeta + Meiotempo | London"),
                GigDate(2026, 8, 18),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-07-23/7a6ab6c3-0bb0-468a-b158-60bdc49f59c7.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(signatureBrewHaggerston.id, GigUrl("https://dice.fm/event/duck-dive-festival-2027-london-26th-feb-signature-brew-haggerston-london-tickets")),
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
            source = TwoTwoNineGigsSource(cachedClient()),
            size = 75,
            first = Gig(
                GigId(twoTwoNine.id, GigUrl("https://dice.fm/event/lun8-14th-aug-229-london-tickets")),
                GigTitle("LUN8"),
                GigDate(2026, 8, 14),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-06-23/baa8fed2-8ece-4006-83d7-f9610c6622f3.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(twoTwoNine.id, GigUrl("https://dice.fm/event/leo-kottke-9th-jun-229-london-tickets")),
                GigTitle("Leo Kottke"),
                GigDate(2027, 6, 9),
                PosterUrl("https://dice-media.imgix.net/attachments/2026-06-01/e83611c7-842b-4a07-ae83-b29386d816dc.jpg"),
                GigDescription(""),
            ),
        )
    }
}
