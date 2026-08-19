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
        val yesterday = Gig(id = GigId(theUnderworld.id, "https://example.com/gigs/yesterday"), title = GigTitle("Yesterday Gig"), date = LocalDate.of(2026, 8, 9), posterUrl = PosterUrl("https://example.com/poster.jpg"), description = GigDescription(""))
        val today = Gig(id = GigId(theUnderworld.id, "https://example.com/gigs/today"), title = GigTitle("Today Gig"), date = LocalDate.of(2026, 8, 10), posterUrl = PosterUrl("https://example.com/poster.jpg"), description = GigDescription(""))
        val tomorrow = Gig(id = GigId(theUnderworld.id, "https://example.com/gigs/tomorrow"), title = GigTitle("Tomorrow Gig"), date = LocalDate.of(2026, 8, 11), posterUrl = PosterUrl("https://example.com/poster.jpg"), description = GigDescription(""))

        val gigs = excludeGigsInThePast(listOf(yesterday, today, tomorrow), today = LocalDate.of(2026, 8, 10))

        expectThat(gigs).containsExactlyInAnyOrder(today, tomorrow)
    }

    @Test
    fun `keeps gigs up to a year ahead and drops the ones past it`() {
        fun gig(date: LocalDate) =
            Gig(id = GigId(theUnderworld.id, "https://example.com/gigs/$date"), title = GigTitle("Gig"), date = date, posterUrl = PosterUrl("https://example.com/poster.jpg"), description = GigDescription(""))

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
            Gig(id = GigId(theUnderworld.id, "https://example.com/gigs/late-gig"), title = GigTitle("Late Gig"), date = LocalDate.of(2026, 9, 1), posterUrl = PosterUrl("https://example.com/images/late-gig.jpg"), description = GigDescription("")),
            Gig(id = GigId(theUnderworld.id, "https://example.com/gigs/early-gig-one"), title = GigTitle("Early Gig One"), date = LocalDate.of(2026, 8, 8), posterUrl = PosterUrl("https://example.com/images/early-gig-one.jpg"), description = GigDescription("")),
            Gig(id = GigId(theGrace.id, "https://example.com/gigs/early-gig-two"), title = GigTitle("Early Gig Two"), date = LocalDate.of(2026, 8, 8), posterUrl = PosterUrl("https://example.com/images/early-gig-two.jpg"), description = GigDescription("")),
        )
        val renderer = HandlebarsTemplates().CachingClasspath()

        val html = renderer(GigsView(groupGigsByDate(gigs)))

        approver.assertApproved(Response(OK).body(html))
    }

    @Test
    fun `sorts gigs alphabetically within a day, ignoring case`() {
        fun gig(title: String) =
            Gig(id = GigId(theUnderworld.id, "https://example.com/gigs/$title"), title = GigTitle(title), date = LocalDate.of(2026, 8, 8), posterUrl = PosterUrl("https://example.com/poster.jpg"), description = GigDescription(""))

        val groups = groupGigsByDate(listOf(gig("zebra"), gig("Apple"), gig("banana"), gig("Cherry")))

        expectThat(groups.single().gigs.map { it.title }).containsExactly("Apple", "banana", "Cherry", "zebra")
    }
}
