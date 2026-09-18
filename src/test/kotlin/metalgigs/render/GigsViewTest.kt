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
import strikt.assertions.contains
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test

@ExtendWith(ApprovalTest::class)
class GigsViewTest {

    @Test
    fun `excludes gigs before today but keeps gigs on today`() {
        val yesterday = Gig(GigId(theUnderworld.id, GigUrl("https://example.com/gigs/yesterday")), GigTitle("Yesterday Gig"), GigDate(2026, 8, 9), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val today = Gig(GigId(theUnderworld.id, GigUrl("https://example.com/gigs/today")), GigTitle("Today Gig"), GigDate(2026, 8, 10), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val tomorrow = Gig(GigId(theUnderworld.id, GigUrl("https://example.com/gigs/tomorrow")), GigTitle("Tomorrow Gig"), GigDate(2026, 8, 11), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))

        val gigs = excludeGigsInThePast(listOf(yesterday, today, tomorrow), today = LocalDate.of(2026, 8, 10))

        expectThat(gigs).containsExactlyInAnyOrder(today, tomorrow)
    }

    @Test
    fun `keeps gigs up to a year ahead and drops the ones past it`() {
        fun gig(date: LocalDate) =
            Gig(GigId(theUnderworld.id, GigUrl("https://example.com/gigs/$date")), GigTitle("Gig"), GigDate(date), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))

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
            Gig(GigId(theUnderworld.id, GigUrl("https://example.com/gigs/late-gig")), GigTitle("Late Gig"), GigDate(2026, 9, 1), PosterUrl("https://example.com/images/late-gig.jpg"), GigDescription("")),
            Gig(GigId(theUnderworld.id, GigUrl("https://example.com/gigs/early-gig-one")), GigTitle("Early Gig One"), GigDate(2026, 8, 8), PosterUrl("https://example.com/images/early-gig-one.jpg"), GigDescription("")),
            Gig(GigId(theGrace.id, GigUrl("https://example.com/gigs/early-gig-two")), GigTitle("Early Gig Two"), GigDate(2026, 8, 8), PosterUrl("https://example.com/images/early-gig-two.jpg"), GigDescription("")),
        )
        val renderer = HandlebarsTemplates().CachingClasspath()

        val html = renderer(GigsView(groupGigsByDate(gigs)))

        approver.assertApproved(Response(OK).body(html))
    }

    @Test
    fun `a title of names run together can break after each slash`() {
        val gig = Gig(GigId(theUnderworld.id, GigUrl("https://example.com/gigs/man-woman-chainsaw")), GigTitle("Man/Woman/Chainsaw"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val renderer = HandlebarsTemplates().CachingClasspath()

        val html = renderer(GigsView(groupGigsByDate(listOf(gig))))

        expectThat(html).contains("Man/<wbr>Woman/<wbr>Chainsaw")
    }

    @Test
    fun `a title whose slashes have a space beside them is left as it is`() {
        val gig = Gig(GigId(theUnderworld.id, GigUrl("https://example.com/gigs/ditz")), GigTitle("Ditz / Enola Gay /Iguana Death Cult"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val renderer = HandlebarsTemplates().CachingClasspath()

        val html = renderer(GigsView(groupGigsByDate(listOf(gig))))

        expectThat(html).contains("Ditz / Enola Gay /Iguana Death Cult")
    }

    // Why the shape is the poster's, and bounded: docs/adr/0014-a-card-is-drawn-to-its-posters-own-shape.md
    @Test
    fun `a card takes its own image's ratio`() {
        val gig = gigAt(GigDate(2026, 8, 8))

        val card = groupGigsByDate(listOf(gig), mapOf(gig.id to 0.8)).single().gigs.single()

        expectThat(card.cardRatio).isEqualTo("0.800")
    }

    @Test
    fun `a card narrower than 4 by 5 or wider than 16 by 9 is cropped to those bounds`() {
        val a3 = gigAt(GigDate(2026, 8, 8))
        val panorama = gigAt(GigDate(2026, 8, 9))

        val cards = groupGigsByDate(listOf(a3, panorama), mapOf(a3.id to 0.707, panorama.id to 2.5))

        expectThat(cards.flatMap { it.gigs }.map { it.cardRatio }).containsExactly("0.800", "1.778")
    }

    // a publish that failed leaves an image the render can't measure, and a square is the shape
    // every card had before this one was read off the image
    @Test
    fun `a card whose image could not be measured stays square`() {
        val gig = gigAt(GigDate(2026, 8, 8))

        val card = groupGigsByDate(listOf(gig), emptyMap()).single().gigs.single()

        expectThat(card.cardRatio).isEqualTo("1.000")
    }

    // The property is read by CSS, which takes no decimal comma, so it can't be written in whatever
    // locale the machine rendering the page happens to run in.
    @Test
    fun `a card's ratio is written with a decimal point whatever the machine's locale`() {
        val gig = gigAt(GigDate(2026, 8, 8))
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val card = groupGigsByDate(listOf(gig), mapOf(gig.id to 1.5)).single().gigs.single()
            expectThat(card.cardRatio).isEqualTo("1.500")
        } finally {
            Locale.setDefault(default)
        }
    }

    private fun gigAt(date: GigDate) =
        Gig(GigId(theUnderworld.id, GigUrl("https://example.com/gigs/$date")), GigTitle("Gig"), date, PosterUrl("https://example.com/poster.jpg"), GigDescription(""))

    @Test
    fun `sorts gigs alphabetically within a day, ignoring case`() {
        fun gig(title: String) =
            Gig(GigId(theUnderworld.id, GigUrl("https://example.com/gigs/$title")), GigTitle(title), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))

        val groups = groupGigsByDate(listOf(gig("zebra"), gig("Apple"), gig("banana"), gig("Cherry")))

        expectThat(groups.single().gigs.map { it.title }).containsExactly("Apple", "banana", "Cherry", "zebra")
    }
}
