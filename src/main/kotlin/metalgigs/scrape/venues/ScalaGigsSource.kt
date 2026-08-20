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
                val gigUrl = link.attr("abs:href")

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

    // this category currently spans two pages (36 + 19 events), found by following the page's own
    // "next" link rather than guessing at a query parameter - the same sidebar also links a handful
    // of upcoming shows outside .tb-event-item, which .select scopes past. maxPages exists only to
    // bound a pathological site bug; the real stop condition is the next link disappearing
    private val maxPages = 10

    // .entry-content leads with the venue's ticketing and access furniture - price and sale status,
    // door times, an age/ID/access table, "Read our guide to buying and using tickets" - and closes
    // with calendar links, all of it identical across listings and on a short one longer than the
    // copy. The gig's own copy is what follows the "About <artist>" heading, on every listing read.
    // The header box is kept for the promoter and support acts, which the listing's title leaves out.
    // Its own date line and buy/info links go with the rest of the furniture though: the date is
    // already a field on the gig, and reaching the classifier as prose only reads as a second date.
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
