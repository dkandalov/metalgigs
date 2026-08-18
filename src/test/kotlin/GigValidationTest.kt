import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.time.LocalDate
import kotlin.test.Test

class GigValidationTest {

    private val someVenue = VenueId("Some Venue")

    private fun gig(title: GigTitle, url: String, description: String) =
        Gig(id = GigId(someVenue, url), title = title, date = LocalDate.of(2026, 8, 8), imageUrl = PosterUrl("https://example.com/poster.jpg"), description = description)

    private val realText = "Doom night with support from three bands, doors 7pm."

    private fun GigsCheck.problemsIn(gigs: List<Gig>, previous: List<Gig> = emptyList()) =
        problems(someVenue, gigs, previous)

    private fun GigsCheck.problemsFor(gigs: List<Gig>) =
        problemsIn(gigs).map { problem -> problem.detail to problem.gigs.map { it.id.url } }

    private fun GigsCheck.gigsFlaggedIn(gigs: List<Gig>) = problemsIn(gigs).flatMap { it.gigs }.map { it.id.url }

    // what a selector that has stopped matching leaves behind - Jsoup's text() returns "" rather
    // than failing, so nothing else in the pipeline would notice
    @Test
    fun `flags a gig whose title or description didn't parse at all`() {
        val gigs = listOf(
            gig(title = GigTitle("Real Title"), url = "https://example.com/a", description = realText),
            gig(title = GigTitle("   "), url = "https://example.com/b", description = realText),
            gig(title = GigTitle("Real Title"), url = "https://example.com/c", description = "   "),
        )

        expectThat(MisshapenGigsCheck.problemsFor(gigs)).isEqualTo(
            listOf("no title" to listOf("https://example.com/b"), "no description" to listOf("https://example.com/c")),
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

        expectThat(MisshapenGigsCheck.gigsFlaggedIn(gigs)).isEqualTo(listOf("https://example.com/a"))
    }

    // a selector that grabbed the whole page brings the nav and footer with it. The one that must
    // survive is the longest description in the log, at 7492 chars
    @Test
    fun `flags a description long enough to be a whole page rather than a gig's own copy`() {
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "nav footer ".repeat(3000)),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = "x".repeat(7492)),
        )

        expectThat(MisshapenGigsCheck.gigsFlaggedIn(gigs)).isEqualTo(listOf("https://example.com/a"))
    }

    // all three verbatim from the log, and all three sit within the length bounds - the cookie wall
    // at 5990 chars falls between two real band biographies, so only their wording tells them apart
    @Test
    fun `flags a description that is a cookie wall, a bot check, or a JavaScript notice`() {
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "Facebook ... Allow the use of cookies from Facebook in this browser? We use cookies and similar technologies to help provide and improve content on Meta Products."),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = """{"response":"identify"}"""),
            gig(title = GigTitle("C"), url = "https://example.com/c", description = "Gigantic Tickets - Bot Check Enable JavaScript and cookies to continue"),
            gig(title = GigTitle("D"), url = "https://example.com/d", description = "tixr.com Please enable JS and disable any ad blocker"),
        )

        expectThat(MisshapenGigsCheck.problemsFor(gigs))
            .isEqualTo(listOf("description is a cookie or bot wall, not gig copy" to gigs.map { it.id.url }))
    }

    // real gig copy links these often enough that neither can be a marker for boilerplate
    @Test
    fun `leaves gig copy that merely mentions terms or a privacy policy alone`() {
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "Doom night, doors 7pm. Tickets subject to our terms and conditions and privacy policy."),
        )

        expectThat(MisshapenGigsCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    // two characters is a real gig title and nine a real description, so neither has a minimum
    // beyond being non-blank
    @Test
    fun `leaves very short text alone`() {
        val gigs = listOf(gig(title = GigTitle("LP"), url = "https://example.com/a", description = "Lion Babe"))

        expectThat(MisshapenGigsCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    // the same text on unrelated events is text belonging to the venue rather than to any of them,
    // and too short a stretch of it for the contamination check's six-word windows to see
    @Test
    fun `flags gigs given the same description as another gig they have nothing to do with`() {
        val venueBlurb = "Camden's home of live music since 1975"
        val gigs = listOf(
            gig(title = GigTitle("Primus"), url = "https://example.com/a", description = venueBlurb),
            gig(title = GigTitle("The Black Keys"), url = "https://example.com/b", description = venueBlurb),
            gig(title = GigTitle("Kawehi"), url = "https://example.com/c", description = realText),
        )

        expectThat(SharedDescriptionCheck.problemsFor(gigs))
            .isEqualTo(listOf("\"$venueBlurb\"" to listOf("https://example.com/a", "https://example.com/b")))
    }

    // the report shows the shared text in place of the gigs' own copy, cut short so a page of it
    // can't fill a run's output
    @Test
    fun `reports the shared text itself, shortened`() {
        val venueBlurb = "Camden's home of live music since 1975, open seven nights a week, doors at 7pm"
        val gigs = listOf(
            gig(title = GigTitle("Primus"), url = "https://example.com/a", description = venueBlurb),
            gig(title = GigTitle("The Black Keys"), url = "https://example.com/b", description = venueBlurb),
        )

        expectThat(SharedDescriptionCheck.problemsFor(gigs))
            .isEqualTo(listOf("\"${venueBlurb.take(60)}...\"" to listOf("https://example.com/a", "https://example.com/b")))
    }

    // a venue booking the same thing twice writes one blurb for both dates, and says so in the
    // titles - a two-night stand at The Garage and a weekly club night, both verbatim from the log
    @Test
    fun `leaves a repeat booking alone, however its title is spelt across the dates`() {
        val alarm = "Tickets are now available for THE ALARM 2.0 at The Garage, over two days."
        val club = "Simply the best hits and dancefloor fillers from the 80s, 10:30pm - 2:30am."
        val gigs = listOf(
            gig(title = GigTitle("THE ALARM 2.0 - REUNION (NIGHT 1)"), url = "https://example.com/a", description = alarm),
            gig(title = GigTitle("THE ALARM 2.0 - REUNION (NIGHT 2)"), url = "https://example.com/b", description = alarm),
            gig(title = GigTitle("Paper Dress 80s Club"), url = "https://example.com/c", description = club),
            gig(title = GigTitle("Paper Dress 80’s Club"), url = "https://example.com/d", description = club),
        )

        expectThat(SharedDescriptionCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    // an event page that says nothing about its gig is a poster-only gig, not a repeated description
    @Test
    fun `does not read gigs with no captured description as sharing one`() {
        val gigs = listOf(
            gig(title = GigTitle("Primus"), url = "https://example.com/a", description = ""),
            gig(title = GigTitle("The Black Keys"), url = "https://example.com/b", description = ""),
        )

        expectThat(SharedDescriptionCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    // a bot wall trips two checks at once - it is not gig copy, and it is the same non-copy on every
    // gig at the venue - and it is the first that says what to go and fix
    @Test
    fun `reports a gig under the first check to claim it, and withholds it whoever claimed it`() {
        val botWall = "tixr.com Please enable JS and disable any ad blocker"
        val gigs = listOf(
            gig(title = GigTitle("Suntrap Sessions 2026"), url = "https://example.com/a", description = botWall),
            gig(title = GigTitle("Weekly Wednesday Pub Quiz"), url = "https://example.com/b", description = botWall),
            gig(title = GigTitle("The Beertles"), url = "https://example.com/c", description = botWall),
        )

        val validation = validateGigs(mapOf(someVenue to gigs))

        expectThat(validation.reports.map { it.heading }).isEqualTo(listOf(MisshapenGigsCheck.heading))
        expectThat(validation.withheld).isEqualTo(gigs.toSet())
    }

    @Test
    fun `withholds every gig at a contaminated venue, not only those measured as contaminated`() {
        val boilerplate = "Sign up for news, offers and events at our venue today"
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "Doom metal night with support. $boilerplate"),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = "Thrash revival show tonight. $boilerplate"),
            gig(title = GigTitle("C"), url = "https://example.com/c", description = "Black metal ritual returns. $boilerplate"),
            gig(title = GigTitle("D"), url = "https://example.com/d", description = "Grindcore all-dayer, twelve bands from noon, tickets on the door"),
        )

        val validation = validateGigs(mapOf(someVenue to gigs))

        expectThat(validation.reports.single().problems.single().detail).isEqualTo("3 of 4 gig(s) mostly shared text")
        expectThat(validation.withheld).isEqualTo(gigs.toSet())
    }

    @Test
    fun `flags a venue whose gigs share a long stretch of boilerplate text`() {
        val boilerplate = "Sign up for news, offers and events at our venue today"
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "Doom metal night with support. $boilerplate"),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = "Thrash revival show tonight. $boilerplate"),
            gig(title = GigTitle("C"), url = "https://example.com/c", description = "Black metal ritual returns. $boilerplate"),
        )

        expectThat(ContaminationCheck.problemsFor(gigs))
            .isEqualTo(listOf("3 of 3 gig(s) mostly shared text" to gigs.map { it.id.url }))
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

        expectThat(ContaminationCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    @Test
    fun `does not flag a venue whose gigs have genuinely distinct descriptions`() {
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "Doom metal night with support from local acts"),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = "Thrash revival show featuring three touring bands"),
            gig(title = GigTitle("C"), url = "https://example.com/c", description = "Black metal ritual with atmospheric visuals tonight"),
        )

        expectThat(ContaminationCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    @Test
    fun `does not flag a venue with too few gigs to tell a coincidence from real contamination`() {
        val boilerplate = "Sign up for news, offers and events at our venue today"
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "Doom metal night. $boilerplate"),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = "Thrash revival show. $boilerplate"),
        )

        expectThat(ContaminationCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    @Test
    fun `ignores gigs with no captured description, including toward the minimum gig count`() {
        val boilerplate = "Sign up for news, offers and events at our venue today"
        val gigs = listOf(
            gig(title = GigTitle("A"), url = "https://example.com/a", description = "Doom metal night. $boilerplate"),
            gig(title = GigTitle("B"), url = "https://example.com/b", description = "Thrash revival show. $boilerplate"),
            gig(title = GigTitle("C"), url = "https://example.com/c", description = ""),
        )

        expectThat(ContaminationCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    // a listing selector that no longer matches returns nothing rather than failing, so the venue
    // would otherwise leave the run without a word said about it
    @Test
    fun `flags a venue that scraped without listing anything`() {
        val known = listOf(gig(title = GigTitle("A"), url = "https://example.com/a", description = realText))

        expectThat(EmptyListingCheck.problemsIn(gigs = emptyList(), previous = known))
            .isEqualTo(listOf(GigsProblem(someVenue, "listed nothing, though the log holds 1 gig(s) for it", emptySet())))
    }

    // a venue nothing has ever been logged for is more likely one with nothing announced yet than a
    // source that has broken, and the report says which it's looking at
    @Test
    fun `says so when the log holds nothing for the venue either`() {
        expectThat(EmptyListingCheck.problemsIn(gigs = emptyList()).map { it.detail })
            .isEqualTo(listOf("listed nothing, and the log holds no gigs for it either"))
    }

    @Test
    fun `leaves a venue that listed something alone`() {
        val gigs = listOf(gig(title = GigTitle("A"), url = "https://example.com/a", description = realText))

        expectThat(EmptyListingCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    // the trap: an empty listing has no gigs to withhold, and "have all of these already been
    // withheld?" is vacuously true of no gigs at all, which would drop the report
    @Test
    fun `reports an empty listing though it withholds nothing`() {
        val validation = validateGigs(mapOf(someVenue to emptyList()))

        expectThat(validation.reports.map { it.heading }).isEqualTo(listOf(EmptyListingCheck.heading))
        expectThat(validation.withheld).isEqualTo(emptySet())
    }

    // a venue that has stopped listing is told from one that never listed by what the log holds for
    // it, so handing a check another venue's gigs - or none at all - would swap one story for the other
    @Test
    fun `hands each venue the log's gigs for that venue alone`() {
        val otherVenue = VenueId("Other Venue")
        val known = gig(title = GigTitle("A"), url = "https://example.com/a", description = realText)

        val validation = validateGigs(
            scraped = mapOf(someVenue to emptyList(), otherVenue to emptyList()),
            previous = listOf(known),
        )

        expectThat(validation.reports.single().problems.map { it.venueId to it.detail }).isEqualTo(
            listOf(
                someVenue to "listed nothing, though the log holds 1 gig(s) for it",
                otherVenue to "listed nothing, and the log holds no gigs for it either",
            ),
        )
    }

    // a venue the run never reached says nothing about whether its source works
    @Test
    fun `says nothing about a venue that was not scraped`() {
        val validation = validateGigs(scraped = emptyMap())

        expectThat(validation.reports).isEqualTo(emptyList())
    }
}
