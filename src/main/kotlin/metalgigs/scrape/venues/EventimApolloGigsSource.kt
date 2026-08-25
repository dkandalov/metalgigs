package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Month

val eventimApollo = Venue(VenueId("eventim-apollo"), "Eventim Apollo")

class EventimApolloGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = eventimApollo
    // The listing carries every month it knows about in one page - the month bar above the cards
    // filters what's already there rather than navigating - so there's nothing to paginate through.
    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select(".search-item")
            .map { item ->
                val gigUrl = gigUrlFrom(item.select("a.cover-link").attr("abs:href"), "https://www.eventimapollo.com/events/")
                Gig(
                    GigId(venue.id, gigUrl),
                    titleFrom(item.select(".card__title").text()),
                    startDateOf(item.select("p.date").text()),
                    // The narrow-breakpoint <picture> is a generic house image on some listings.
                    posterUrlFrom(gigUrl, item.select(".card__image img").first()?.attr("abs:src")),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }

    private val url = "https://www.eventimapollo.com/events/"

    // a single day is written "Thursday 20th August 2026", with the month in full; a run of dates is
    // written "Aug 16th - Aug 23rd 2026", abbreviated and with the year only on the end date. Only
    // the start date is used, and ordinal suffixes are discarded.
    private val singleDate = Regex("""(\d{1,2})\w{2}\s+(\w+)\s+(\d{4})""")
    private val rangeStart = Regex("""^(\w+)\s+(\d{1,2})\w{2}""")
    private val rangeEnd = Regex("""-\s*(\w+)\s+\d{1,2}\w{2}\s+(\d{4})""")

    private fun monthNamed(name: String): Month = monthsByShortName[name] ?: Month.valueOf(name.uppercase())

    // Written once, on the end date, so a range crossing new year would otherwise date its start a
    // year late: "Dec 28th - Jan 3rd 2027" starts in 2026. Nothing in the listing crosses one today,
    // which is exactly why it has to be handled here rather than noticed later.
    private fun startDateOf(text: String): GigDate {
        val trimmed = text.trim()
        val end = rangeEnd.find(trimmed)
            ?: return singleDate.find(trimmed)!!.destructured.let { (day, month, year) ->
                GigDate(year.toInt(), monthNamed(month), day.toInt())
            }

        val (startMonthName, startDay) = rangeStart.find(trimmed)!!.destructured
        val (endMonthName, yearText) = end.destructured
        val startMonth = monthNamed(startMonthName)
        val year = if (startMonth > monthNamed(endMonthName)) yearText.toInt() - 1 else yearText.toInt()
        return GigDate(year, startMonth, startDay.toInt())
    }

    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    internal fun eventPageContent(page: Document) = page.select(".event-hero .variable-color.mt-sm").textOrNull()
}
