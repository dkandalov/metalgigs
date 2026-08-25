package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

val roundhouse = Venue(VenueId("roundhouse"), "Roundhouse")

class RoundhouseGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = roundhouse
    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select(".event-card")
            .map { item ->
                val link = item.select(".event-card__link")
                val (day, monthName, year) = datePattern.find(item.select(".event-card__date").text())!!.destructured

                val gigUrl = gigUrlFrom(link.attr("abs:href"), "https://www.roundhouse.org.uk/whats-on/")
                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(item.select(".event-card__title").text()),
                    GigDate(2000 + year.toInt(), monthsByShortName.getValue(monthName), day.toInt()),
                    posterUrlFrom(gigUrl, item.select(".event-card__image img").attr("abs:src")),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }

    // type=event drops the venue's youth-programme courses, which share 145 words of standard access
    // and bursary copy embedded in each course's own text block - unscopeable, and enough to read as
    // site-wide boilerplate.
    private val url = "https://www.roundhouse.org.uk/whats-on/?type=event"

    // e.g. "Wed 12 Aug 26" or a multi-day range "Wed 12 Aug 26–Fri 14 Aug 26"; only the start date is used
    private val datePattern = Regex("""(\d{1,2}) (\w{3}) (\d{2})""")

    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    internal fun eventPageContent(page: Document): String? {
        val content = page.select(".event-hero__heading-wrapper, section.event-about")
        // Both sit inside .event-about rather than beside it.
        content.select(".layout-block--related-events-list, .layout-block--event-listing-card").remove()
        return content.textOrNull()
    }
}
