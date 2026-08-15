import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.time.LocalDate
import kotlin.test.Test

class GigValidationTest {

    private fun gig(title: String, url: String, description: String) =
        Gig(id = GigId(VenueId("Some Venue"), url), title = title, date = LocalDate.of(2026, 8, 8), imageUrl = "", description = description)

    @Test
    fun `flags a venue whose gigs share a long stretch of boilerplate text`() {
        val boilerplate = "Sign up for news, offers and events at our venue today"
        val gigs = listOf(
            gig(title = "A", url = "https://example.com/a", description = "Doom metal night with support. $boilerplate"),
            gig(title = "B", url = "https://example.com/b", description = "Thrash revival show tonight. $boilerplate"),
            gig(title = "C", url = "https://example.com/c", description = "Black metal ritual returns. $boilerplate"),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(mapOf("Some Venue" to 3))
    }

    // real venues often print the same short policy line (age restriction, ID requirement) on every
    // event page as genuine content - that alone isn't the sitewide-nav-and-footer bug this looks for
    @Test
    fun `does not flag a venue whose gigs merely share a short disclaimer within much longer unique text`() {
        val disclaimer = "Under 18s must be accompanied by an adult at all times"
        val gigs = listOf(
            gig(title = "A", url = "https://example.com/a", description = "unique-a ".repeat(100) + disclaimer),
            gig(title = "B", url = "https://example.com/b", description = "unique-b ".repeat(100) + disclaimer),
            gig(title = "C", url = "https://example.com/c", description = "unique-c ".repeat(100) + disclaimer),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(emptyMap())
    }

    @Test
    fun `does not flag a venue whose gigs have genuinely distinct descriptions`() {
        val gigs = listOf(
            gig(title = "A", url = "https://example.com/a", description = "Doom metal night with support from local acts"),
            gig(title = "B", url = "https://example.com/b", description = "Thrash revival show featuring three touring bands"),
            gig(title = "C", url = "https://example.com/c", description = "Black metal ritual with atmospheric visuals tonight"),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(emptyMap())
    }

    @Test
    fun `does not flag a venue with too few gigs to tell a coincidence from real contamination`() {
        val boilerplate = "Sign up for news, offers and events at our venue today"
        val gigs = listOf(
            gig(title = "A", url = "https://example.com/a", description = "Doom metal night. $boilerplate"),
            gig(title = "B", url = "https://example.com/b", description = "Thrash revival show. $boilerplate"),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(emptyMap())
    }

    @Test
    fun `ignores gigs with no captured description, including toward the minimum gig count`() {
        val boilerplate = "Sign up for news, offers and events at our venue today"
        val gigs = listOf(
            gig(title = "A", url = "https://example.com/a", description = "Doom metal night. $boilerplate"),
            gig(title = "B", url = "https://example.com/b", description = "Thrash revival show. $boilerplate"),
            gig(title = "C", url = "https://example.com/c", description = ""),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(emptyMap())
    }
}
