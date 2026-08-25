package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Month

val scala = Venue(VenueId("scala"), "Scala")

class ScalaGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = scala
    override fun latestGigs(): List<Gig> {
        val gigs = mutableListOf<Gig>()
        var pageUrl: String? = url
        var pagesFetched = 0

        while (pageUrl != null && pagesFetched < maxPages) {
            val page = Jsoup.parse(fetchPage(client, pageUrl), pageUrl)
            pagesFetched++
            gigs += page.select(".tb-event-item").map { item ->
                val (day, monthName, year) = datePattern.find(item.select(".date").text())!!.destructured
                val link = item.select("h2 a")
                val gigUrl = gigUrlFrom(link.attr("abs:href"), "https://scala.co.uk/events/")

                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(link.text()),
                    GigDate(year.toInt(), Month.valueOf(monthName.uppercase()), day.toInt()),
                    posterUrlFrom(gigUrl, backgroundImageUrlPattern.find(item.select(".tb-event-feature-pic").attr("style"))?.groupValues?.get(1)),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
            pageUrl = page.select(".em-pagination a.next").attr("abs:href").ifBlank { null }
        }
        return gigs
    }

    private val url = "https://scala.co.uk/events/categories/live-music/"

    // e.g. "19th August 2026" - full month name, ordinal suffix discarded
    private val datePattern = Regex("""(\d{1,2})\w*\s+(\w+)\s+(\d{4})""")

    // e.g. background-image:url('...') - the poster is a css background rather than an img element
    private val backgroundImageUrlPattern = Regex("""url\('([^']+)'\)""")

    // Why paging follows the listing's own link: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
    private val maxPages = 10

    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    private val ticketingFurniture =
        "p.event-date, p.age-restrictions, p.event-time, p.guide-to, p.add-calendar, .button, .left-morebox, .right-morebox"

    internal fun eventPageContent(page: Document): String? {
        val content = page.select(".event-post .entry-content").firstOrNull()?.clone() ?: return null
        content.select(ticketingFurniture).remove()
        val lineup = content.select(".tb-event-headerbox").also { it.remove() }.text()

        val children = content.children()
        val about = children.indexOfFirst { it.tagName() == "h3" && it.text().startsWith("About", ignoreCase = true) }
        val copy = (if (about >= 0) children.drop(about) else children).joinToString(" ") { it.text() }

        return "$lineup $copy".trim().ifBlank { null }
    }
}
