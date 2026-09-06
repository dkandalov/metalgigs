package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

val theLexington = Venue(VenueId("the-lexington"), "The Lexington")

class TheLexingtonGigsSource(private val client: HttpHandler, private val year: Int) : GigsSource {
    override val venue = theLexington

    override fun latestGigs(): List<Gig> {
        var currentYear = year
        var previousMonth: Month? = null

        return Jsoup.parse(fetchPage(client, url), url)
            // the grid is padded to a whole row with empty cards carrying neither title nor date
            .select(".grid-item, .grid-item-club")
            .filter { it.selectFirst(".event-title") != null }
            .mapNotNull { card ->
                // counted over club nights too, they being listed in the same date order - the
                // fewer rows the count is read from, the further a month can be skipped
                val (weekday, monthName, day) = datePattern.find(card.select(".event-date").text())!!.destructured
                val month = Month.valueOf(monthName.uppercase())
                if (previousMonth != null && month < previousMonth) currentYear++
                previousMonth = month

                // Why a night that isn't a gig is dropped: docs/adr/0007-a-description-is-the-gigs-own-copy.md
                if (card.selectFirst(".club-label") != null) return@mapNotNull null

                val gigUrl = gigUrlFrom(card.select("a.link-box").attr("abs:href"), eventsPath)
                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(card.select(".event-title").text()),
                    dateOn(currentYear, month, day.toInt(), weekday, gigUrl),
                    posterUrlFrom(gigUrl, card.select(".image-wrapper img").attr("abs:src")),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
    }

    // "all" rather than the "gigs" the venue's own menu links to, its filtered listings being lossy
    // (ADR 7): what a card is comes off the card instead.
    private val url = "https://thelexington.co.uk/events.php?type=all"
    private val eventsPath = "https://thelexington.co.uk/event.php?id="

    // e.g. "Sun September 06, 19:00" - no year anywhere on the page or the card
    private val datePattern = Regex("""(\w+)\s+(\w+)\s+(\d{1,2}),""")

    // Why the year is counted forward, and checked against the weekday: docs/adr/0010-a-date-is-read-per-venue-and-a-missing-year-is-inferred.md
    private fun dateOn(year: Int, month: Month, day: Int, weekday: String, gigUrl: GigUrl): GigDate {
        val date = LocalDate.of(year, month, day)
        val actual = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        check(actual == weekday) {
            "$gigUrl is listed on a $weekday and $date is a $actual - the year counted across the listing " +
                "no longer lands on the day the card prints, the listing having stopped being in date order"
        }
        return GigDate(date)
    }

    // e.g. "live", "play live", "Mary Middlefield plays live"
    private val playsLiveOnly = Regex("""^(?:live|(?:[\w'’&.\-]+ ){0,5}plays? live)[.,!]*$""", RegexOption.IGNORE_CASE)

    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    internal fun eventPageContent(page: Document) =
        page.selectOrNull(".text-content") { content ->
            content.select("p").text().takeUnless { playsLiveOnly.matches(it.trim()) }.orEmpty()
        }
}
