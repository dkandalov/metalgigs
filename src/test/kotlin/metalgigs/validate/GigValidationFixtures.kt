package metalgigs.validate

import metalgigs.*

// each gig its own url and description, so the day they land on is the only thing about them any
// check has to say anything about
internal fun gigsOn(date: GigDate, count: Int) = (1..count).map {
    Gig(
        GigId(someVenue, GigUrl("https://example.com/$date/$it")),
        GigTitle("Gig $it"),
        date,
        PosterUrl("https://example.com/poster.jpg"),
        GigDescription("Gig $it"),
    )
}

// a day apart each, so a venue sharing one picture is the only thing odd about them
internal fun gigsSharing(poster: PosterUrl, count: Int, from: Int = 1) = (from until from + count).map {
    gig(title = GigTitle("Gig $it"), url = "https://example.com/$it", description = "Gig $it", poster = poster, date = GigDate(2026, 8, it))
}

// Every check is asked about one venue at a time, so a test names the gigs and nothing else. These
// return what a check said rather than the check's own shapes: the detail beside the urls it named,
// which is what a report would show, and reads in a test as what someone would go and look at.
internal fun GigsCheck.problemsIn(gigs: List<Gig>, previous: List<Gig> = emptyList()) =
    problems(someVenue, gigs, previous)

internal fun GigsCheck.problemsFor(gigs: List<Gig>) =
    problemsIn(gigs).map { problem -> problem.detail to problem.gigs.map { it.id.url.value } }

internal fun GigsCheck.gigsFlaggedIn(gigs: List<Gig>) = problemsIn(gigs).flatMap { it.gigs }.map { it.id.url.value }
