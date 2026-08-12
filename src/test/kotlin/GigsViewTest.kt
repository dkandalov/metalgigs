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
        val yesterday = GigEvent(title = "Yesterday Gig", venue = "Venue A", year = 2026, month = "Aug", day = "09", url = "https://example.com/gigs/yesterday", imageUrl = "")
        val today = GigEvent(title = "Today Gig", venue = "Venue A", year = 2026, month = "Aug", day = "10", url = "https://example.com/gigs/today", imageUrl = "")
        val tomorrow = GigEvent(title = "Tomorrow Gig", venue = "Venue A", year = 2026, month = "Aug", day = "11", url = "https://example.com/gigs/tomorrow", imageUrl = "")

        val gigs = excludeGigsInThePast(listOf(yesterday, today, tomorrow), today = LocalDate.of(2026, 8, 10))

        expectThat(gigs).containsExactlyInAnyOrder(today, tomorrow)
    }

    @Test
    fun `renders gigs grouped by date as html`(approver: Approver) {
        val gigs = listOf(
            GigEvent(title = "Late Gig", venue = "Venue A", year = 2026, month = "Sep", day = "01", url = "https://example.com/gigs/late-gig", imageUrl = "https://example.com/images/late-gig.jpg"),
            GigEvent(title = "Early Gig One", venue = "Venue A", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/early-gig-one", imageUrl = "https://example.com/images/early-gig-one.jpg"),
            GigEvent(title = "Early Gig Two", venue = "Venue B", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/early-gig-two", imageUrl = "https://example.com/images/early-gig-two.jpg"),
        )
        val renderer = HandlebarsTemplates().CachingClasspath()

        val html = renderer(GigsView(groupGigsByDate(gigs)))

        approver.assertApproved(Response(OK).body(html))
    }

    @Test
    fun `sorts gigs alphabetically within a day, ignoring case`() {
        fun gig(title: String) =
            GigEvent(title = title, venue = "Venue A", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/$title", imageUrl = "")

        val groups = groupGigsByDate(listOf(gig("zebra"), gig("Apple"), gig("banana"), gig("Cherry")))

        expectThat(groups.single().gigs.map { it.title }).containsExactly("Apple", "banana", "Cherry", "zebra")
    }
}
