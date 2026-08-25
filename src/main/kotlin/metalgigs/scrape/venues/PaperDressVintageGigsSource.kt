package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Month

val paperDressVintage = Venue(VenueId("paper-dress-vintage"), "Paper Dress Vintage")

class PaperDressVintageGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = paperDressVintage
    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select("[data-event-month]")
            .flatMap { monthBlock ->
                val (monthName, year) = monthBlock.attr("data-event-month").split(" ")
                monthBlock.select(".events__event").map { item ->
                    val day = dayPattern.find(item.select("p.margin__bottom--1").text())!!.groupValues[1]
                    val thumbnailUrl = backgroundImageUrlPattern.find(item.select(".event__poster").attr("style"))?.groupValues?.get(1)
                    val gigUrl = gigUrlFrom(item.attr("abs:href"), "https://paperdressvintage.co.uk/")

                    Gig(
                        GigId(venue.id, gigUrl),
                        GigTitle(item.select("h4").text()),
                        GigDate(year.toInt(), Month.valueOf(monthName.uppercase()), day.toInt()),
                        posterUrlFrom(gigUrl, thumbnailUrl?.let { thumbnailSuffixPattern.replace(it, "$1") }),
                        fetchDescription(client, gigUrl, ::eventPageContent),
                    )
                }
            }

    private val url = "https://paperdressvintage.co.uk/by-night"

    // e.g. "Fri 14 Aug" - no year on the card itself; each month's block of cards is wrapped in a
    // div carrying the year, e.g. data-event-month="August 2026"
    private val dayPattern = Regex("""\w{3}\s+(\d{1,2})\s+\w{3}""")
    private val backgroundImageUrlPattern = Regex("""url\('([^']+)'\)""")

    // Why the thumbnail suffix is stripped: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
    private val thumbnailSuffixPattern = Regex("""-lbox-\d+x\d+-FFF(\.\w+)$""")

    internal fun eventPageContent(page: Document) = page.select(".event__content").textOrNull()
}
