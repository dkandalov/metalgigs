package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Month

val islingtonAssemblyHall = Venue(VenueId("islington-assembly-hall"), "Islington Assembly Hall")

class IslingtonAssemblyHallGigsSource(private val client: HttpHandler, private val year: Int) : GigsSource {
    override val venue = islingtonAssemblyHall
    override fun latestGigs(): List<Gig> {
        val gigs = mutableListOf<Gig>()
        var pageUrl: String? = url
        var pagesFetched = 0
        var currentYear = year
        var previousMonth: Month? = null

        while (pageUrl != null && pagesFetched < maxPages) {
            val page = Jsoup.parse(fetchPage(client, pageUrl), pageUrl)
            pagesFetched++
            gigs += page.select("li.event__item").map { item ->
                // a card prints "Fri 21 Aug" and no year anywhere; the boundary the count crosses
                // falls mid-page-4.
                // Why the year is counted forward: docs/adr/0010-a-date-is-read-per-venue-and-a-missing-year-is-inferred.md
                val month = monthsByShortName.getValue(item.select(".event__item__date__month").text())
                if (previousMonth != null && month < previousMonth) currentYear++
                previousMonth = month

                val link = item.select("a.event__item__title")
                val gigUrl = gigUrlFrom(link.attr("abs:href"), "https://islingtonassemblyhall.co.uk/events/")
                // the poster is the background of an anchor the theme lazy-loads, so it has no src
                // at all until its own JavaScript runs - only data-src
                val thumbnailUrl = item.select("a.event__item__background").attr("abs:data-src")

                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(link.text()),
                    GigDate(currentYear, month, item.select(".event__item__date__numeric").text().toInt()),
                    posterUrlFrom(gigUrl, thumbnailSuffixPattern.replace(thumbnailUrl, "$1")),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
            pageUrl = page.select("a.entry__pagination__action[data-js-infinite-next]").attr("abs:href").ifBlank { null }
        }
        return gigs
    }

    private val url = "https://islingtonassemblyhall.co.uk/events/"

    // eighteen to a page, walked by following the listing's own next link. maxPages exists only to
    // bound a pathological site bug; the real stop condition is that link disappearing
    private val maxPages = 10

    // Why the thumbnail suffix is stripped: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
    private val thumbnailSuffixPattern = Regex("""-\d+x\d+-c-center(\.\w+)$""")

    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    private val ticketingBoilerplate = Regex(
        """by purchasing a ticket to this event|subject to a venue levy|^\*?this is an? \d+\+ event""",
        RegexOption.IGNORE_CASE,
    )

    // the "Presented by <promoter>" line is left in: it names who booked the show, which is the one
    // thing some of these listings say beyond the boilerplate
    internal fun eventPageContent(page: Document) =
        page.selectOrNull(".event__description") { description ->
            description.select("p")
                .filterNot { ticketingBoilerplate.containsMatchIn(it.text().trim()) }
                .joinToString(" ") { it.text() }
                .trim()
        }
}
