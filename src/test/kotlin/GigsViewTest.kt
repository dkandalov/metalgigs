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
        val yesterday = Gig(id = GigId(theUnderworld.id, "https://example.com/gigs/yesterday"), title = GigTitle("Yesterday Gig"), date = LocalDate.of(2026, 8, 9), imageUrl = "", description = "")
        val today = Gig(id = GigId(theUnderworld.id, "https://example.com/gigs/today"), title = GigTitle("Today Gig"), date = LocalDate.of(2026, 8, 10), imageUrl = "", description = "")
        val tomorrow = Gig(id = GigId(theUnderworld.id, "https://example.com/gigs/tomorrow"), title = GigTitle("Tomorrow Gig"), date = LocalDate.of(2026, 8, 11), imageUrl = "", description = "")

        val gigs = excludeGigsInThePast(listOf(yesterday, today, tomorrow), today = LocalDate.of(2026, 8, 10))

        expectThat(gigs).containsExactlyInAnyOrder(today, tomorrow)
    }

    @Test
    fun `renders gigs grouped by date as html`(approver: Approver) {
        val gigs = listOf(
            Gig(id = GigId(theUnderworld.id, "https://example.com/gigs/late-gig"), title = GigTitle("Late Gig"), date = LocalDate.of(2026, 9, 1), imageUrl = "https://example.com/images/late-gig.jpg", description = ""),
            Gig(id = GigId(theUnderworld.id, "https://example.com/gigs/early-gig-one"), title = GigTitle("Early Gig One"), date = LocalDate.of(2026, 8, 8), imageUrl = "https://example.com/images/early-gig-one.jpg", description = ""),
            Gig(id = GigId(theGrace.id, "https://example.com/gigs/early-gig-two"), title = GigTitle("Early Gig Two"), date = LocalDate.of(2026, 8, 8), imageUrl = "https://example.com/images/early-gig-two.jpg", description = ""),
        )
        val renderer = HandlebarsTemplates().CachingClasspath()

        val html = renderer(GigsView(groupGigsByDate(gigs)))

        approver.assertApproved(Response(OK).body(html))
    }

    @Test
    fun `sorts gigs alphabetically within a day, ignoring case`() {
        fun gig(title: String) =
            Gig(id = GigId(theUnderworld.id, "https://example.com/gigs/$title"), title = GigTitle(title), date = LocalDate.of(2026, 8, 8), imageUrl = "", description = "")

        val groups = groupGigsByDate(listOf(gig("zebra"), gig("Apple"), gig("banana"), gig("Cherry")))

        expectThat(groups.single().gigs.map { it.title }).containsExactly("Apple", "banana", "Cherry", "zebra")
    }
}
