package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.Month

val electricBrixton = Venue(VenueId("electric-brixton"), "Electric Brixton")

class ElectricBrixtonGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = electricBrixton

    override fun latestGigs(): List<Gig> {
        val gigs = mutableListOf<Gig>()
        var pageUrl: String? = url
        var pagesFetched = 0

        while (pageUrl != null && pagesFetched < maxPages) {
            val page = Jsoup.parse(fetchPage(client, pageUrl), pageUrl)
            pagesFetched++
            gigs += page.select(".fl-post-grid-post").map { item ->
                val (day, monthName, year) = datePattern.find(item.select(".event-date").text())!!.destructured
                val link = item.select(".event-title a")
                val gigUrl = link.attr("abs:href")

                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(link.text()),
                    LocalDate.of(year.toInt(), Month.valueOf(monthName.uppercase()), day.toInt()),
                    // .event-image also holds an empty img for the rollover animation, so the
                    // thumbnail is taken from its own container rather than the first img in there
                    posterUrlFrom(gigUrl, item.select(".uabb-post-thumbnail img").attr("abs:src")),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
            pageUrl = page.select("a.next.page-numbers").attr("abs:href").ifBlank { null }
        }
        return gigs
    }

    private val url = "https://www.electricbrixton.uk.com/events/"

    // e.g. "28th August 2026" - full month name, ordinal suffix discarded
    private val datePattern = Regex("""(\d{1,2})\w*\s+(\w+)\s+(\d{4})""")

    // twelve to a page, walked by following the listing's own next link rather than guessing at
    // /events/page/N/. maxPages exists only to bound a pathological site bug; the real stop
    // condition is that link disappearing
    private val maxPages = 10

    // the page around this holds the venue's own furniture - door times, price, age and ID policy,
    // a mailing-list form, the footer - none of it about the gig, and on a short listing longer
    // than the copy
    internal fun eventPageContent(page: Document) = page.select(".event-context").textOrNull()
}
