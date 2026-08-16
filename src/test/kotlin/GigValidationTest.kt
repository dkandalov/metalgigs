import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.time.LocalDate
import kotlin.test.Test

class GigValidationTest {

    private fun gig(title: GigTitle, url: String, description: String) =
        Gig(id = GigId(VenueId("Some Venue"), url), title = title, date = LocalDate.of(2026, 8, 8), imageUrl = "", description = description)

    private val realText = "Doom night with support from three bands, doors 7pm."

    // what a selector that has stopped matching leaves behind - Jsoup's text() returns "" rather
    // than failing, so nothing else in the pipeline would notice
    @Test
    fun `flags a gig whose title or description didn't parse at all`() {
        val gigs = listOf(
            gig(title = GigTitle("Real Title"), url = "https://example.com/a", description = realText),
            gig(title = GigTitle("   "), url = "https://example.com/b", description = realText),
            gig(title = GigTitle("Real Title"), url = "https://example.com/c", description = "   "),
        )

        expectThat(misshapenGigs(gigs).mapKeys { (gig, _) -> gig.id.url }).isEqualTo(
            mapOf("https://example.com/b" to "no title", "https://example.com/c" to "no description"),
        )
    }

    // a selector matching a card's container instead of its heading takes the date, price and blurb
    // along with the title. The one that must survive is the longest title in the log, at 103 chars
    @Test
    fun `flags a title long enough to be a whole card rather than a heading`() {
        val wholeCard = "Doom Night ".repeat(30)
        val gigs = listOf(
            gig(title = GigTitle(wholeCard), url = "https://example.com/a", description = realText),
            gig(title = GigTitle("FOREVER NU - 25th anniversary of Toxicity & Iowa special! Chop Suey, Slip-Not, A7Xperience, Propa Roach"), url = "https://example.com/b", description = realText),
        )

        expectThat(misshapenGigs(gigs).keys.map { it.id.url }).isEqualTo(listOf("https://example.com/a"))
    }

    // a selector that grabbed the whole page brings the nav and footer with it. The one that must
    // survive is the longest description in the log, at 7492 chars
    @Test
    fun `flags a description long enough to be a whole page rather than a gig's own copy`() {
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "nav footer ".repeat(3000)),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = "x".repeat(7492)),
        )

        expectThat(misshapenGigs(gigs).keys.map { it.id.url }).isEqualTo(listOf("https://example.com/a"))
    }

    // all three verbatim from the log, and all three sit within the length bounds - the cookie wall
    // at 5990 chars falls between two real band biographies, so only their wording tells them apart
    @Test
    fun `flags a description that is a cookie wall, a bot check, or a JavaScript notice`() {
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "Facebook ... Allow the use of cookies from Facebook in this browser? We use cookies and similar technologies to help provide and improve content on Meta Products."),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = """{"response":"identify"}"""),
            gig(title = GigTitle("C"), url = "https://example.com/c", description = "Gigantic Tickets - Bot Check Enable JavaScript and cookies to continue"),
        )

        expectThat(misshapenGigs(gigs).values.toSet())
            .isEqualTo(setOf("description is a cookie or bot wall, not gig copy"))
    }

    // real gig copy links these often enough that neither can be a marker for boilerplate
    @Test
    fun `leaves gig copy that merely mentions terms or a privacy policy alone`() {
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "Doom night, doors 7pm. Tickets subject to our terms and conditions and privacy policy."),
        )

        expectThat(misshapenGigs(gigs)).isEqualTo(emptyMap())
    }

    // two characters is a real gig title and nine a real description, so neither has a minimum
    // beyond being non-blank
    @Test
    fun `leaves very short text alone`() {
        val gigs = listOf(gig(title = GigTitle("LP"), url = "https://example.com/a", description = "Lion Babe"))

        expectThat(misshapenGigs(gigs)).isEqualTo(emptyMap())
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
