package metalgigs.scrape

import metalgigs.*
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import kotlin.test.Test

class GigValidationTest {

    private val someVenue = VenueId("Some Venue")

    private fun gig(title: GigTitle, url: String, description: String, poster: PosterUrl = PosterUrl("https://example.com/poster.jpg"), date: GigDate = GigDate(2026, 8, 8)) =
        Gig(GigId(someVenue, url), title = title, date, poster, GigDescription(description))

    private val realText = "Doom night with support from three bands, doors 7pm."

    private fun GigsCheck.problemsIn(gigs: List<Gig>, previous: List<Gig> = emptyList()) =
        problems(someVenue, gigs, previous)

    private fun GigsCheck.problemsFor(gigs: List<Gig>) =
        problemsIn(gigs).map { problem -> problem.detail to problem.gigs.map { it.id.url } }

    private fun GigsCheck.gigsFlaggedIn(gigs: List<Gig>) = problemsIn(gigs).flatMap { it.gigs }.map { it.id.url }

    // The Black Heart's own event pages print a Bandcamp embed's code as visible text below the gig
    // copy, so .text() faithfully returns it - verbatim from the log
    @Test
    fun `flags a description holding markup the page showed as text`() {
        val embed = """MORAG TONG DRUIDESS OUTBACK <a href="https://moragtong.bandcamp.com/album/grieve-5">Grieve by Morag Tong</a>"""
        val gigs = listOf(gig(title = GigTitle("Morag Tong"), url = "https://example.com/a", description = embed))

        expectThat(UnparsedTextCheck.problemsFor(gigs))
            .isEqualTo(listOf("1 description(s) hold unparsed markup, e.g. https://example.com/a" to emptyList()))
    }

    // a description is never published - it is what the classifier reads - so a venue's broken embed
    // is told about rather than costing the site a real metal gig
    @Test
    fun `keeps a gig whose description holds markup`() {
        val gigs = listOf(gig(title = GigTitle("Erdling"), url = "https://example.com/a", description = "<p>Plus support</p>"))

        expectThat(validateGigs(mapOf(someVenue to gigs)).withheld).isEqualTo(emptySet())
    }

    // a title is published, so markup in one is withheld like any other parsing failure
    @Test
    fun `flags and withholds a title holding markup`() {
        val gigs = listOf(gig(title = GigTitle("<strong>Doom Night</strong>"), url = "https://example.com/a", description = realText))

        expectThat(UnparsedTextCheck.problemsFor(gigs))
            .isEqualTo(listOf("title holds unparsed markup" to listOf("https://example.com/a")))
        expectThat(validateGigs(mapOf(someVenue to gigs)).withheld).isEqualTo(gigs.toSet())
    }

    // both verbatim from Ovo Arena's listing: a tour that brackets its own name is not a tag, and
    // the naive pattern for one reads both of these as markup
    @Test
    fun `leaves a tour that brackets its name in angle brackets alone`() {
        val gigs = listOf(
            gig(title = GigTitle("2026-27 TAEMIN WORLD TOUR <LiMiNaL>"), url = "https://example.com/a", description = realText),
            gig(title = GigTitle("ITZY 3RD WORLD TOUR <TUNNEL VISION>"), url = "https://example.com/b", description = realText),
            gig(title = GigTitle("It's Never Over, Jeff Buckley > Screening"), url = "https://example.com/c", description = realText),
        )

        expectThat(UnparsedTextCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    @Test
    fun `flags an html entity the page never decoded`() {
        val gigs = listOf(gig(title = GigTitle("A-Sun Amissa &amp; Lauren Mason"), url = "https://example.com/a", description = realText))

        expectThat(UnparsedTextCheck.problemsFor(gigs))
            .isEqualTo(listOf("title holds an html entity" to listOf("https://example.com/a")))
    }

    // 112 titles and 411 descriptions in the log hold a bare ampersand, so only a closed entity counts
    @Test
    fun `leaves a bare ampersand alone`() {
        val gigs = listOf(
            gig(title = GigTitle("Gurt & Bleeding Antlers"), url = "https://example.com/a", description = "Rock & roll, doors 7pm & support at 8"),
        )

        expectThat(UnparsedTextCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    // the second reads as a UTF-8 misread, the first is the accented title the log really holds -
    // telling them apart is the whole difficulty, since both look like letters with marks on
    @Test
    fun `flags text read in the wrong charset`() {
        val gigs = listOf(
            gig(title = GigTitle("Mägick Ritüal"), url = "https://example.com/a", description = realText),
            gig(title = GigTitle("Doom Night"), url = "https://example.com/b", description = "Support from Fanchonâ€™s band, Â£10 on the door"),
        )

        expectThat(UnparsedTextCheck.problemsFor(gigs))
            .isEqualTo(listOf("1 description(s) hold mis-decoded characters, e.g. https://example.com/b" to emptyList()))
    }

    // the accents the log actually holds, correctly decoded, are ordinary letters
    @Test
    fun `leaves correctly decoded accents alone`() {
        val gigs = listOf(
            gig(title = GigTitle("Parish + Mägick Ritüal"), url = "https://example.com/a", description = realText),
            gig(title = GigTitle("Fat Freddy's Drop"), url = "https://example.com/b", description = "Kamil Bednarek w Londynie, 18-nastka Bednarka"),
        )

        expectThat(UnparsedTextCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    // each gig its own url and description, so the day they land on is the only thing about them any
    // check has to say anything about
    private fun gigsOn(date: GigDate, count: Int) = (1..count).map {
        Gig(
            GigId(someVenue, "https://example.com/$date/$it"),
            GigTitle("Gig $it"),
            date,
            PosterUrl("https://example.com/poster.jpg"),
            GigDescription("Gig $it"),
        )
    }

    // a date parse that has drifted returns the same wrong date for every gig it reads, so a whole
    // listing lands on one day with nothing wrong about any gig of it
    @Test
    fun `flags a venue showing more gigs on one day than it could`() {
        expectThat(CrowdedDayCheck.problemsFor(gigsOn(GigDate(2026, 9, 12), 6)).map { it.first })
            .isEqualTo(listOf("6 gig(s) on 2026-09-12"))
    }

    // Roundhouse's Centre 59 Theatre Week ran four in a day, the most any venue in the log has, so
    // five is a venue having a busier evening than any of them yet rather than a source gone wrong
    @Test
    fun `leaves a venue's busiest real day alone`() {
        expectThat(CrowdedDayCheck.problemsIn(gigsOn(GigDate(2026, 9, 12), 5))).isEqualTo(emptyList())
    }

    @Test
    fun `counts each day on its own rather than the whole listing`() {
        val gigs = gigsOn(GigDate(2026, 9, 12), 5) + gigsOn(GigDate(2026, 9, 13), 5)

        expectThat(CrowdedDayCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    // a day that piled up condemns the listing it arrived in rather than just itself: the gigs either
    // side of it came off the same parse and are only ones whose dates happened to land apart
    @Test
    fun `withholds the venue's whole listing, not only the crowded day`() {
        val crowded = gigsOn(GigDate(2026, 9, 12), 6)
        val theNextDay = gigsOn(GigDate(2026, 9, 13), 1)

        val validation = validateGigs(mapOf(someVenue to crowded + theNextDay))

        expectThat(validation.reports.map { it.heading }).isEqualTo(listOf(CrowdedDayCheck.heading))
        expectThat(validation.withheld).isEqualTo((crowded + theNextDay).toSet())
    }

    // a day apart each, so a venue sharing one picture is the only thing odd about them
    private fun gigsSharing(poster: PosterUrl, count: Int, from: Int = 1) = (from until from + count).map {
        gig(title = GigTitle("Gig $it"), url = "https://example.com/$it", description = "Gig $it", poster = poster, date = GigDate(2026, 8, it))
    }

    // a poster selector that has stopped matching takes the whole listing with it, and every other
    // check reads the gigs' text rather than what is shown beside it
    @Test
    fun `flags a venue whose gigs are nearly all published under one picture`() {
        val logo = PosterUrl("https://example.com/assets/venue-logo.png?w=400")

        expectThat(SharedPosterCheck.problemsFor(gigsSharing(logo, 21)).map { it.first })
            .isEqualTo(listOf("21 gig(s) share venue-logo.png"))
    }

    // a weekly night reuses one poster for every date it runs, which is the shape that sets the
    // threshold rather than the bug it looks for
    @Test
    fun `leaves a residency sharing its poster right up to the threshold alone`() {
        val residency = PosterUrl("https://example.com/karaoke-sundays.jpg")

        expectThat(SharedPosterCheck.problemsIn(gigsSharing(residency, 20))).isEqualTo(emptyList())
    }

    // two residencies at one venue are two pictures, and neither says anything about the other
    @Test
    fun `counts each poster on its own rather than every gig that shares any`() {
        val gigs = gigsSharing(PosterUrl("https://example.com/a.jpg"), 15) +
            gigsSharing(PosterUrl("https://example.com/b.jpg"), 15, from = 16)

        expectThat(SharedPosterCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    @Test
    fun `withholds the venue's whole listing, not only the gigs under the shared picture`() {
        val logo = PosterUrl("https://example.com/venue-logo.png")
        val shared = gigsSharing(logo, 21)
        val ownPoster = gig(title = GigTitle("Primus"), url = "https://example.com/own", description = "Primus", poster = PosterUrl("https://example.com/primus.jpg"))

        val validation = validateGigs(mapOf(someVenue to shared + ownPoster))

        expectThat(validation.reports.map { it.heading }).isEqualTo(listOf(SharedPosterCheck.heading))
        expectThat(validation.withheld).isEqualTo((shared + ownPoster).toSet())
    }

    // a paging loop that re-serves a page, or a gig that appears in a "featured" strip as well as
    // the run of them - the log would take both copies and every projection quietly keep one
    @Test
    fun `flags a gig its source listed more than once`() {
        val listedTwice = gig(title = GigTitle("Primus"), url = "https://example.com/a", description = realText)
        val gigs = listOf(
            listedTwice,
            listedTwice,
            gig(title = GigTitle("Kawehi"), url = "https://example.com/b", description = realText),
        )

        expectThat(DuplicateGigsCheck.problemsFor(gigs))
            .isEqualTo(listOf("listed 2 times" to listOf("https://example.com/a")))
    }

    // the worse of the two, and the reason the count alone won't do: the source built two different
    // gigs from one url, so which one the log keeps is decided by which was scraped last
    @Test
    fun `says so when the copies its source listed are not the same gig`() {
        val gigs = listOf(
            gig(title = GigTitle("Primus"), url = "https://example.com/a", description = realText),
            gig(title = GigTitle("Primus - SOLD OUT"), url = "https://example.com/a", description = realText),
        )

        expectThat(DuplicateGigsCheck.problemsFor(gigs).map { it.first })
            .isEqualTo(listOf("listed 2 times, and not identically"))
    }

    // two gigs at one venue are two gigs, however alike a venue's own booking makes them look - only
    // the url they are identified by says they are the same one twice
    @Test
    fun `leaves a listing whose gigs each appear once alone`() {
        val gigs = listOf(
            gig(title = GigTitle("Primus"), url = "https://example.com/a", description = realText),
            gig(title = GigTitle("Primus"), url = "https://example.com/b", description = realText),
        )

        expectThat(DuplicateGigsCheck.problemsIn(gigs)).isEqualTo(emptyList())
    }

    @Test
    fun `withholds every copy of a gig its source listed more than once`() {
        val gigs = listOf(
            gig(title = GigTitle("Primus"), url = "https://example.com/a", description = realText),
            gig(title = GigTitle("Primus - SOLD OUT"), url = "https://example.com/a", description = realText),
        )

        val validation = validateGigs(mapOf(someVenue to gigs))

        expectThat(validation.reports.map { it.heading }).isEqualTo(listOf(DuplicateGigsCheck.heading))
        expectThat(validation.withheld).isEqualTo(gigs.toSet())
    }

    // what a selector that has stopped matching leaves behind - Jsoup's text() returns "" rather
    // than failing, so nothing else in the pipeline would notice
    @Test
    fun `flags a gig whose description didn't parse at all`() {
        val gigs = listOf(
            gig(title = GigTitle("Real Title"), url = "https://example.com/a", description = realText),
            gig(title = GigTitle("Real Title"), url = "https://example.com/b", description = "   "),
        )

        expectThat(MisshapenGigsCheck.problemsFor(gigs)).isEqualTo(
            listOf("no description" to listOf("https://example.com/b")),
        )
    }

    // a selector matching a card's container instead of its heading takes the date, price and blurb
    // along with the title. The one that must survive is the longest title in the log, at 103 chars
    @Test
    fun `flags a title long enough to be a whole card rather than a heading`() {
        val wholeCard = List(30) { "Doom Night" }.joinToString(" ")
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
