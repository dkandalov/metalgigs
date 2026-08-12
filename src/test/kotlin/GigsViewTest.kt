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
        val yesterday = GigEvent(id = GigId("Venue A", "https://example.com/gigs/yesterday"), title = "Yesterday Gig", year = 2026, month = "Aug", day = "09", imageUrl = "")
        val today = GigEvent(id = GigId("Venue A", "https://example.com/gigs/today"), title = "Today Gig", year = 2026, month = "Aug", day = "10", imageUrl = "")
        val tomorrow = GigEvent(id = GigId("Venue A", "https://example.com/gigs/tomorrow"), title = "Tomorrow Gig", year = 2026, month = "Aug", day = "11", imageUrl = "")

        val gigs = excludeGigsInThePast(listOf(yesterday, today, tomorrow), today = LocalDate.of(2026, 8, 10))

        expectThat(gigs).containsExactlyInAnyOrder(today, tomorrow)
    }

    @Test
    fun `renders gigs grouped by date as html`(approver: Approver) {
        val gigs = listOf(
            GigEvent(id = GigId("Venue A", "https://example.com/gigs/late-gig"), title = "Late Gig", year = 2026, month = "Sep", day = "01", imageUrl = "https://example.com/images/late-gig.jpg"),
            GigEvent(id = GigId("Venue A", "https://example.com/gigs/early-gig-one"), title = "Early Gig One", year = 2026, month = "Aug", day = "08", imageUrl = "https://example.com/images/early-gig-one.jpg"),
            GigEvent(id = GigId("Venue B", "https://example.com/gigs/early-gig-two"), title = "Early Gig Two", year = 2026, month = "Aug", day = "08", imageUrl = "https://example.com/images/early-gig-two.jpg"),
        )
        val renderer = HandlebarsTemplates().CachingClasspath()

        val html = renderer(GigsView(groupGigsByDate(gigs)))

        approver.assertApproved(Response(OK).body(html))
    }

    @Test
    fun `sorts gigs alphabetically within a day, ignoring case`() {
        fun gig(title: String) =
            GigEvent(id = GigId("Venue A", "https://example.com/gigs/$title"), title = title, year = 2026, month = "Aug", day = "08", imageUrl = "")

        val groups = groupGigsByDate(listOf(gig("zebra"), gig("Apple"), gig("banana"), gig("Cherry")))

        expectThat(groups.single().gigs.map { it.title }).containsExactly("Apple", "banana", "Cherry", "zebra")
    }
}
