package metalgigs.scrape

import metalgigs.*
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.MOVED_PERMANENTLY
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import kotlin.test.Test

// Why a gig's url is its identity: docs/adr/0005-a-gig-is-identified-by-the-url-it-lives-at.md
// Why the thresholds are these numbers: docs/adr/0004-thresholds-are-measured-against-the-log-and-dated.md
class GigCorrectionTest {

    private val today = GigDate(2026, 9, 1)

    private val lolaAsListed = gig(GigTitle("LOLA (AUS) | London"), "https://dice.fm/event/lola-aus-london-10th-sep", realText, date = GigDate(2026, 9, 10))
    private val lolaRelisted = gig(GigTitle("LOLA (AUS) + Lucky Hit | London"), "https://dice.fm/event/eo6xmw-lola-aus-lucky-hit-london-10th-sep", realText, date = GigDate(2026, 9, 10))

    // Cart and Horses keeps event 534086 where it was and rewrites the slug from the title, so the
    // old url 301s to the new one: the venue names the gig's new home and nothing has to be guessed.
    @Test
    fun `takes a redirect as the gig's new url`() {
        val replacements = replacementsIn(
            scraped = listOf(lolaRelisted),
            previous = listOf(lolaAsListed),
            from = today,
            missingGigSays = { MissingGig.MovedTo(lolaRelisted.id.url) },
        )

        expectThat(replacements).isEqualTo(listOf(lolaAsListed.id to lolaRelisted.id))
    }

    // a venue that redirects a dropped gig to its "what's on" page, or to next year's festival, is
    // pointing away from the gig rather than at where it went
    @Test
    fun `ignores a redirect to something the venue isn't listing as a gig`() {
        val replacements = replacementsIn(
            scraped = listOf(lolaRelisted),
            previous = listOf(lolaAsListed),
            from = today,
            missingGigSays = { MissingGig.MovedTo(GigUrl("https://dice.fm/venue/signature-brew-haggerston")) },
        )

        expectThat(replacements).isEqualTo(emptyList())
    }

    // dice.fm deletes the old page rather than redirecting it, so the gig on that night whose title
    // is mostly the same words is what stands in for the venue saying so
    @Test
    fun `pairs a deleted url with the gig that night whose title it mostly shares`() {
        val replacements = replacementsIn(
            scraped = listOf(lolaRelisted),
            previous = listOf(lolaAsListed),
            from = today,
            missingGigSays = { MissingGig.Gone },
        )

        expectThat(replacements).isEqualTo(listOf(lolaAsListed.id to lolaRelisted.id))
    }

    // two Camden Fringe shows at Union Chapel on one night share "camden" and "fringe" and nothing
    // else, which is the closest two different gigs came in the log as of 2026-08-21
    @Test
    fun `leaves a deleted url alone when that night's gigs are other gigs`() {
        val dropped = gig(GigTitle("Camden Fringe: Kari - a Modern Mythical Tale 28 AUG"), "https://example.com/kari", realText, PosterUrl("https://example.com/kari.jpg"), GigDate(2026, 9, 10))
        val another = gig(GigTitle("Camden Fringe: Getting to Iona 28 AUG"), "https://example.com/iona", realText, PosterUrl("https://example.com/iona.jpg"), GigDate(2026, 9, 10))
        val asked = mutableListOf<GigUrl>()

        val replacements = replacementsIn(listOf(another), listOf(dropped), today) {
            asked += it
            MissingGig.Gone
        }

        expectThat(replacements).isEqualTo(emptyList())
        expectThat(asked.toList()).isEqualTo(emptyList())
    }

    // the request is what says whether a gig moved, so it is spent only where there is an answer to
    // be had: a gig cancelled outright, with nothing like it listed that night, could not have been
    // paired whatever its url said - and would be asked again every run, since a run that pairs
    // nothing records nothing
    @Test
    fun `asks nothing about a gig with nothing like it listed that night`() {
        val cancelled = gig(GigTitle("Primus"), "https://example.com/primus", realText, PosterUrl("https://example.com/primus.jpg"), GigDate(2026, 9, 10))
        val unrelated = gig(GigTitle("Kawehi"), "https://example.com/kawehi", realText, PosterUrl("https://example.com/kawehi.jpg"), GigDate(2026, 9, 10))
        val asked = mutableListOf<GigUrl>()

        val replacements = replacementsIn(listOf(unrelated), listOf(cancelled), today) {
            asked += it
            MissingGig.MovedTo(unrelated.id.url)
        }

        expectThat(asked.toList()).isEqualTo(emptyList())
        expectThat(replacements).isEqualTo(emptyList())
    }

    // Dice re-uploaded LOLA (AUS)'s picture two days after the first, so a poster alone won't do; a
    // venue that rewrites more of a title than it keeps, as Cart and Horses did at 0.44, is why the
    // picture is asked about too
    @Test
    fun `asks about a gig that shares only its picture with that night's listing`() {
        val poster = PosterUrl("https://example.com/lesbian-bed-death.jpeg")
        val moved = gig(GigTitle("LESBIAN BED DEATH + SUBATOMIC CHILDREN"), "https://example.com/534086-subatomic", realText, poster, GigDate(2026, 9, 10))
        val relisted = gig(GigTitle("Some Other Billing Entirely"), "https://example.com/534086-better-dead", realText, poster, GigDate(2026, 9, 10))

        val replacements = replacementsIn(listOf(relisted), listOf(moved), today) { MissingGig.Gone }

        expectThat(replacements).isEqualTo(listOf(moved.id to relisted.id))
    }

    // Union Chapel's matinee and evening sittings share a picture, a night and most of a title, and
    // are two gigs - what says so is that the venue is still serving both pages
    @Test
    fun `leaves a gig alone while its own page is still served`() {
        val matinee = gig(GigTitle("Kate Rusby at Christmas: Matinee"), "https://example.com/matinee", realText, date = GigDate(2026, 9, 10))
        val evening = gig(GigTitle("Kate Rusby at Christmas: Evening"), "https://example.com/evening", realText, date = GigDate(2026, 9, 10))

        val replacements = replacementsIn(listOf(evening), listOf(matinee), today) { MissingGig.Live }

        expectThat(replacements).isEqualTo(emptyList())
    }

    // a gig that has moved to another night has moved further than a rewritten title, and pairing it
    // by name alone would take one night's gig for another's
    @Test
    fun `pairs a deleted url only with a gig on its own night`() {
        val otherNight = gig(GigTitle("LOLA (AUS) + Lucky Hit | London"), "https://dice.fm/event/eo6xmw-lola-11th-sep", realText, date = GigDate(2026, 9, 11))

        val replacements = replacementsIn(listOf(otherNight), listOf(lolaAsListed), today) { MissingGig.Gone }

        expectThat(replacements).isEqualTo(emptyList())
    }

    // one request per gig a venue has stopped listing, so what is asked about is what the page could
    // still print: a night already played is neither rendered nor worth a request
    @Test
    fun `asks nothing about a gig that is still listed, or one whose night has passed`() {
        val played = gig(GigTitle("LOLA (AUS) | London"), "https://dice.fm/event/lola-aus-london-1st-aug", realText, date = GigDate(2026, 8, 1))
        val asked = mutableListOf<GigUrl>()

        val replacements = replacementsIn(listOf(lolaRelisted), listOf(played, lolaRelisted), today) {
            asked += it
            MissingGig.Gone
        }

        expectThat(asked.toList()).isEqualTo(emptyList())
        expectThat(replacements).isEqualTo(emptyList())
    }

    // the same night at the same venue, listed twice over: whichever reads as more of the same gig
    @Test
    fun `takes the likeliest of the gigs listed that night`() {
        val unrelated = gig(GigTitle("Doom Night with three bands"), "https://example.com/doom", realText, date = GigDate(2026, 9, 10))

        val replacements = replacementsIn(listOf(unrelated, lolaRelisted), listOf(lolaAsListed), today) { MissingGig.Gone }

        expectThat(replacements).isEqualTo(listOf(lolaAsListed.id to lolaRelisted.id))
    }
    // Cart and Horses answers with "/news-offers-events/534086-...", where dice.fm answers with the
    // whole url. Read as it stands, a relative one matches no gig the venue listed and the move goes
    // unnoticed - which is exactly what happened the first time this ran against the real site.
    @Test
    fun `resolves a relative redirect against the url it asked about`() {
        val moved = gig(GigTitle("LESBIAN BED DEATH + SUBATOMIC CHILDREN"), "https://www.cartandhorses.london/news-offers-events/534086-subatomic-children/", realText, date = GigDate(2026, 9, 10))
        val relisted = gig(GigTitle("LESBIAN BED DEATH + THE BETTER DEAD"), "https://www.cartandhorses.london/news-offers-events/534086-the-better-dead/", realText, date = GigDate(2026, 9, 10))
        val venue = FakeVenue("/news-offers-events/534086-the-better-dead/")

        val replacements = replacementsIn(listOf(relisted), listOf(moved), today) { url -> missingGigSays(venue, url) }

        expectThat(replacements).isEqualTo(listOf(moved.id to relisted.id))
    }
}

// answers every request with the redirect a venue would, so what is under test is how a Location is
// read rather than what any site does with one
private class FakeVenue(private val location: String) : HttpHandler {
    override fun invoke(request: Request) = Response(MOVED_PERMANENTLY).header("location", location)
}
