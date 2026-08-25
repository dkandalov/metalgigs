package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Month

val electricBallroom = Venue(VenueId("electric-ballroom"), "Electric Ballroom")

class ElectricBallroomGigsSource(private val client: HttpHandler, private val year: Int) : GigsSource {
    override val venue = electricBallroom
    override fun latestGigs(): List<Gig> {
        var currentYear = year
        var previousMonth: Month? = null

        return Jsoup.parse(fetchPage(client, url), url)
            .select(".grid-block.card")
            .map { item ->
                val (day, monthName) = datePattern.find(item.select(".event-date").text())!!.destructured
                val month = Month.valueOf(monthName.uppercase())
                if (previousMonth != null && month < previousMonth) currentYear++
                previousMonth = month

                val gigUrl = gigUrlFrom(item.select(".event-name a").attr("abs:href"), "https://electricballroom.co.uk/")
                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(item.select(".event-name a").text()),
                    GigDate(currentYear, month, day.toInt()),
                    posterUrlFrom(gigUrl, backgroundImageUrlPattern.find(item.select(".grid-image").attr("style"))?.groupValues?.get(1)),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
    }

    private val url = "https://electricballroom.co.uk/whats-on/"

    // dates have no year, e.g. "Thursday 13th August"; ordinal suffix is discarded
    private val datePattern = Regex("""(\d{1,2})\w*\s+(\w+)""")
    private val backgroundImageUrlPattern = Regex("""url\('([^']+)'\)""")

    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    private val agePolicy = Regex("""please note this show is|strictly \d+\+|proof of age|photo id""", RegexOption.IGNORE_CASE)

    internal fun eventPageContent(page: Document): String? {
        val content = page.select(".article-content").firstOrNull()?.clone() ?: return null
        content.select("p").filter { agePolicy.containsMatchIn(it.text()) }.forEach { it.remove() }
        return content.text().ifBlank { null }
    }
}
