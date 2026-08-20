package metalgigs.render

import metalgigs.*
import metalgigs.scrape.venues.theGrace
import metalgigs.scrape.venues.theUnderworld
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.template.HandlebarsTemplates
import org.http4k.testing.ApprovalTest
import org.http4k.testing.Approver
import org.junit.jupiter.api.extension.ExtendWith
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
import java.time.LocalDate
import kotlin.test.Test

@ExtendWith(ApprovalTest::class)
class GigsViewTest {

    @Test
    fun `excludes gigs before today but keeps gigs on today`() {
        val yesterday = Gig(GigId(theUnderworld.id, "https://example.com/gigs/yesterday"), GigTitle("Yesterday Gig"), LocalDate.of(2026, 8, 9), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val today = Gig(GigId(theUnderworld.id, "https://example.com/gigs/today"), GigTitle("Today Gig"), LocalDate.of(2026, 8, 10), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val tomorrow = Gig(GigId(theUnderworld.id, "https://example.com/gigs/tomorrow"), GigTitle("Tomorrow Gig"), LocalDate.of(2026, 8, 11), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))

        val gigs = excludeGigsInThePast(listOf(yesterday, today, tomorrow), today = LocalDate.of(2026, 8, 10))

        expectThat(gigs).containsExactlyInAnyOrder(today, tomorrow)
    }

    @Test
    fun `keeps gigs up to a year ahead and drops the ones past it`() {
        fun gig(date: LocalDate) =
            Gig(GigId(theUnderworld.id, "https://example.com/gigs/$date"), GigTitle("Gig"), date = date, PosterUrl("https://example.com/poster.jpg"), GigDescription(""))

        val today = LocalDate.of(2026, 8, 10)
        val onTheDay = gig(today)
        val aYearOut = gig(LocalDate.of(2027, 8, 10))
        val aDayTooFar = gig(LocalDate.of(2027, 8, 11))
        val yesterday = gig(LocalDate.of(2026, 8, 9))

        val gigs = gigsOnThePage(listOf(yesterday, onTheDay, aYearOut, aDayTooFar), today)

        expectThat(gigs).containsExactlyInAnyOrder(onTheDay, aYearOut)
    }

    @Test
    fun `renders gigs grouped by date as html`(approver: Approver) {
        val gigs = listOf(
            Gig(GigId(theUnderworld.id, "https://example.com/gigs/late-gig"), GigTitle("Late Gig"), LocalDate.of(2026, 9, 1), PosterUrl("https://example.com/images/late-gig.jpg"), GigDescription("")),
            Gig(GigId(theUnderworld.id, "https://example.com/gigs/early-gig-one"), GigTitle("Early Gig One"), LocalDate.of(2026, 8, 8), PosterUrl("https://example.com/images/early-gig-one.jpg"), GigDescription("")),
            Gig(GigId(theGrace.id, "https://example.com/gigs/early-gig-two"), GigTitle("Early Gig Two"), LocalDate.of(2026, 8, 8), PosterUrl("https://example.com/images/early-gig-two.jpg"), GigDescription("")),
        )
        val renderer = HandlebarsTemplates().CachingClasspath()

        val html = renderer(GigsView(groupGigsByDate(gigs)))

        approver.assertApproved(Response(OK).body(html))
    }

    @Test
    fun `sorts gigs alphabetically within a day, ignoring case`() {
        fun gig(title: String) =
            Gig(GigId(theUnderworld.id, "https://example.com/gigs/$title"), GigTitle(title), LocalDate.of(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))

        val groups = groupGigsByDate(listOf(gig("zebra"), gig("Apple"), gig("banana"), gig("Cherry")))

        expectThat(groups.single().gigs.map { it.title }).containsExactly("Apple", "banana", "Cherry", "zebra")
    }
}
