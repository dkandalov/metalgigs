package metalgigs.scrape.venues

import com.ubertob.kondor.json.jsonnode.JsonNodeString
import com.ubertob.kondor.json.jsonnode.parseJsonNode
import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Month

// shared by the two rooms The O2 lists on its own site - the arena and indigo - which differ only
// in the site's own numeric id for each, as the listing page's own "Load More" button carries
internal class TheO2VenueGigsSource(private val client: HttpHandler, private val theO2VenueId: Int, override val venue: Venue) : GigsSource {
    override fun latestGigs(): List<Gig> {
        val gigs = mutableListOf<Gig>()

        for (batch in 0 until maxBatches) {
            val items = Jsoup.parse(eventsHtml(batch * batchSize), siteUrl).select(".eventItem")
            if (items.isEmpty()) break

            gigs += items.map { item ->
                val link = item.select("h3.title a")
                val gigUrl = gigUrlFrom(link.attr("abs:href"), "https://www.theo2.co.uk/events/detail/")

                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(link.text()),
                    startDateOf(item.select(".date").first()!!),
                    // each card carries a 480x281 crop and a square one of at least 564px, and it's
                    // the square that survives render's own square crop rather than being letterboxed
                    posterUrlFrom(gigUrl, item.select(".thumb img.square").attr("abs:src")),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
        }
        return gigs
    }

    // per_page is in the query the site sends, but the server ignores it.
    // Why the batches come from here: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
    private fun batchUrl(offset: Int) = "https://www.theo2.co.uk/events/events_ajax/$offset" +
        "?category=0&venue=$theO2VenueId&team=0&exclude=&per_page=$batchSize&came_from_page=event-list-page"

    private val batchSize = 24

    // Only to bound a pathological site bug; the real stop condition is a batch coming back empty.
    private val maxBatches = 20

    // The response is one JSON string holding the html fragment, rather than the fragment itself.
    private fun eventsHtml(offset: Int): String {
        val body = fetchPage(client, batchUrl(offset)).trim()
        if (body.isEmpty()) return ""
        return (parseJsonNode(body).orThrow() as? JsonNodeString)?.text.orEmpty()
    }

    // The whole page's text is the site's nav, its own venue furniture and a mailing-list form
    // around the copy; this is the one block the promoter writes, and every event page read had one.
    internal fun eventPageContent(page: Document) = page.select(".event_description").textOrNull()

    // A single date writes its day, month and year together. A range writes the year once, on its
    // end date, so a range crossing new year would date its start a year late - "28 Dec - 3 Jan
    // 2027" starts in 2026. Only the start date is used, as at Alexandra Palace and Eventim Apollo.
    internal fun startDateOf(date: Element): GigDate {
        val start = date.select(".m-date__rangeFirst").first() ?: date.select(".m-date__singleDate").first()!!
        val end = date.select(".m-date__rangeLast").first() ?: start
        val startMonth = monthNamed(start)
        val year = end.select(".m-date__year").text().trim().toInt()
        return GigDate(
            if (startMonth > monthNamed(end)) year - 1 else year,
            startMonth,
            start.select(".m-date__day").text().trim().toInt(),
        )
    }

    // The site abbreviates a month except where the abbreviation would save nothing: the arena's
    // listing writes "Jun" as "June" and "Jul" as "July" while every other month is three letters.
    private fun monthNamed(date: Element): Month =
        date.select(".m-date__month").text().trim()
            .let { monthsByShortName[it] ?: Month.valueOf(it.uppercase()) }

    // The fragments carry absolute urls of their own; this only gives Jsoup a base to resolve against
    private val siteUrl = "https://www.theo2.co.uk/"
}

val indigoAtTheO2 = Venue(VenueId("indigo-at-the-o2"), "indigo at The O2")

class IndigoAtTheO2GigsSource(client: HttpHandler) :
    GigsSource by TheO2VenueGigsSource(client, theO2VenueId = 2, venue = indigoAtTheO2)

val theO2Arena = Venue(VenueId("the-o2-arena"), "The O2 Arena")

// The arena's programme is far wider than its indigo room's - boxing, awards ceremonies, family
// shows - and the site's own genre filter has no metal in it, only Rock. So everything it lists is
// taken and left to the classifier, as at Alexandra Palace and Eventim Apollo.
class TheO2ArenaGigsSource(client: HttpHandler) :
    GigsSource by TheO2VenueGigsSource(client, theO2VenueId = 1, venue = theO2Arena)
