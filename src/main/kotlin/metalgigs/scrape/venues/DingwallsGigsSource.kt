package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Month

val dingwalls = Venue(VenueId("dingwalls"), "Dingwalls")

class DingwallsGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = dingwalls

    override fun latestGigs(): List<Gig> {
        val gigs = mutableListOf<Gig>()
        var pageUrl: String? = url
        var pagesFetched = 0

        while (pageUrl != null) {
            val page = Jsoup.parse(fetchPage(client, pageUrl), pageUrl)
            pagesFetched++
            gigs += page.select(".gig").map { item ->
                val (day, monthName, year) = datePattern.find(item.select(".elementor-widget-heading:not(.elementor-widget-theme-post-title)").text())!!.destructured
                val gigUrl = gigUrlFrom(item.select(".elementor-widget-theme-post-title a").attr("abs:href"), "https://dingwalls.com/gig/")

                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(item.select(".elementor-widget-theme-post-title a").text()),
                    GigDate(year.toInt(), Month.valueOf(monthName.uppercase()), day.toInt()),
                    posterUrlFrom(gigUrl, item.select(".elementor-widget-theme-post-featured-image img").attr("abs:src")),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
            pageUrl = nextPageUrl(page)
            check(pageUrl == null || pagesFetched < maxPages) {
                "$venue still offers a next page after $maxPages of them - either its listing has outgrown " +
                    "this bound or its pagination now loops, and both would read as a listing that just ended"
            }
        }
        return gigs
    }

    // The grid loads on scroll, so the page renders 24 of 38 and reads as a whole listing. Its own
    // anchor is what says otherwise, and the page it names is not what ends the walk: the last page
    // still advertises a next one (page 2 of 2 points at /3/, which renders no cards at all), the
    // same shape Windmill Brixton's disabled next link has. data-page against data-max-page is what
    // the page actually settles.
    // Why a listing is paged by the link the page follows: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
    private fun nextPageUrl(page: Document): String? {
        val anchor = page.selectFirst(".e-load-more-anchor") ?: return null
        val at = anchor.attr("data-page").toIntOrNull()
        val last = anchor.attr("data-max-page").toIntOrNull()
        checkNotNull(at) { "The load-more anchor on $url no longer says which page it is" }
        checkNotNull(last) { "The load-more anchor on $url no longer says how many pages there are" }
        return if (at < last) anchor.attr("abs:data-next-page").ifBlank { null } else null
    }

    private val maxPages = 20

    private val url = "https://dingwalls.com/whats-on/"

    // comma placement is inconsistent, e.g. "Wednesday 2nd September 2026", "Tuesday, 8th
    // September 2026", "Saturday 26th September, 2026 (Afternoon Show)"
    private val datePattern = Regex("""(\d{1,2})\w*\s+(\w+),?\s+(\d{4})""")

    internal fun eventPageContent(page: Document) = page.select(".elementor-location-single").textOrNull()
}
