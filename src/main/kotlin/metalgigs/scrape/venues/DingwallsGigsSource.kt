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
    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select(".gig")
            .map { item ->
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

    private val url = "https://dingwalls.com/whats-on/"

    // comma placement is inconsistent, e.g. "Wednesday 2nd September 2026", "Tuesday, 8th
    // September 2026", "Saturday 26th September, 2026 (Afternoon Show)"
    private val datePattern = Regex("""(\d{1,2})\w*\s+(\w+),?\s+(\d{4})""")

    internal fun eventPageContent(page: Document) = page.select(".elementor-location-single").textOrNull()
}
