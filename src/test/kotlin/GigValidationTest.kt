import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.time.LocalDate
import kotlin.test.Test

class GigValidationTest {

    private fun gig(title: GigTitle, url: String, description: String) =
        Gig(id = GigId(VenueId("Some Venue"), url), title = title, date = LocalDate.of(2026, 8, 8), imageUrl = "", description = description)

    // what a selector that has stopped matching leaves behind - Jsoup's text() returns "" rather
    // than failing, so nothing else in the pipeline would notice
    @Test
    fun `flags a gig whose title didn't parse at all`() {
        val gigs = listOf(
            gig(title = GigTitle("Real Title"), url = "https://example.com/a", description = ""),
            gig(title = GigTitle("   "), url = "https://example.com/b", description = ""),
        )

        expectThat(oddlyTitledGigs(gigs).map { it.id.url }).isEqualTo(listOf("https://example.com/b"))
    }

    // a selector matching a card's container instead of its heading takes the date, price and blurb
    // along with the title. The one that must survive is the longest title in the log, at 103 chars
    @Test
    fun `flags a title long enough to be a whole card rather than a heading`() {
        val wholeCard = "Doom Night ".repeat(30)
        val gigs = listOf(
            gig(title = GigTitle(wholeCard), url = "https://example.com/a", description = ""),
            gig(title = GigTitle("FOREVER NU - 25th anniversary of Toxicity & Iowa special! Chop Suey, Slip-Not, A7Xperience, Propa Roach"), url = "https://example.com/b", description = ""),
        )

        expectThat(oddlyTitledGigs(gigs).map { it.id.url }).isEqualTo(listOf("https://example.com/a"))
    }

    // two characters is a real gig title, so there's no minimum beyond being non-blank
    @Test
    fun `leaves a very short title alone`() {
        val gigs = listOf(gig(title = GigTitle("LP"), url = "https://example.com/a", description = ""))

        expectThat(oddlyTitledGigs(gigs)).isEqualTo(emptyList())
    }

    @Test
    fun `flags a venue whose gigs share a long stretch of boilerplate text`() {
        val boilerplate = "Sign up for news, offers and events at our venue today"
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "Doom metal night with support. $boilerplate"),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = "Thrash revival show tonight. $boilerplate"),
            gig(title = GigTitle("C"), url = "https://example.com/c", description = "Black metal ritual returns. $boilerplate"),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(mapOf(VenueId("Some Venue") to 3))
    }

    // real venues often print the same short policy line (age restriction, ID requirement) on every
    // event page as genuine content - that alone isn't the sitewide-nav-and-footer bug this looks for
    @Test
    fun `does not flag a venue whose gigs merely share a short disclaimer within much longer unique text`() {
        val disclaimer = "Under 18s must be accompanied by an adult at all times"
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "unique-a ".repeat(100) + disclaimer),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = "unique-b ".repeat(100) + disclaimer),
            gig(title = GigTitle("C"), url = "https://example.com/c", description = "unique-c ".repeat(100) + disclaimer),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(emptyMap())
    }

    @Test
    fun `does not flag a venue whose gigs have genuinely distinct descriptions`() {
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "Doom metal night with support from local acts"),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = "Thrash revival show featuring three touring bands"),
            gig(title = GigTitle("C"), url = "https://example.com/c", description = "Black metal ritual with atmospheric visuals tonight"),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(emptyMap())
    }

    @Test
    fun `does not flag a venue with too few gigs to tell a coincidence from real contamination`() {
        val boilerplate = "Sign up for news, offers and events at our venue today"
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "Doom metal night. $boilerplate"),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = "Thrash revival show. $boilerplate"),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(emptyMap())
    }

    @Test
    fun `ignores gigs with no captured description, including toward the minimum gig count`() {
        val boilerplate = "Sign up for news, offers and events at our venue today"
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "Doom metal night. $boilerplate"),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = "Thrash revival show. $boilerplate"),
            gig(title = GigTitle("C"), url = "https://example.com/c", description = ""),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(emptyMap())
    }
}
