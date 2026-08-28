package metalgigs.scrape.venues

import com.ubertob.kondor.json.jsonnode.JsonNodeString
import com.ubertob.kondor.json.jsonnode.parseJsonNode
import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.body.form
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

val newCrossInn = Venue(VenueId("new-cross-inn"), "New Cross Inn")

class NewCrossInnGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = newCrossInn
    override fun latestGigs(): List<Gig> {
        val listing = Jsoup.parse(fetchPage(client, url), url)
        val laterMonths = listing.select("ul.next_month_label li.month_label")
            .map { monthlyEvents(it.attr("data-month"), it.attr("data-year")) }
            .map { Jsoup.parse(it, url) }

        return (listOf(listing) + laterMonths)
            .flatMap { it.select("li:has(h3.nci-event-name)") }
            // the month the page opens on is in the dropdown too, so its gigs come back twice -
            // deduped here rather than after the map, so no gig's event page is fetched twice
            .distinctBy { it.select("a:has(h3.nci-event-name)").attr("abs:href") }
            .map { item ->
                val (day, month, year) = datePattern.find(item.select("dd").text())!!.destructured
                val gigUrl = gigUrlFrom(item.select("a:has(h3.nci-event-name)").attr("abs:href"), "https://pit.live/events/")
                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(item.select("h3.nci-event-name").text()),
                    GigDate(year.toInt(), monthsByShortName.getValue(month), day.toInt()),
                    posterUrlFrom(gigUrl, item.select("img").attr("abs:src")),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
    }

    private val url = "https://www.newcrossinn.com/gigs/"

    private val datePattern = Regex("""(\d{2}) (\w{3}) (\d{4})""")

    // Why the copy is decoded this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    internal fun eventPageContent(page: Document): String? {
        val literal = page.select("[x-ref=desc]").firstOrNull()?.attr("x-html")?.trim() ?: return null
        val markup = parseJsonNode("\"${literal.removeSurrounding("'")}\"").orThrow()
        return ((markup as? JsonNodeString)?.text)?.let { Jsoup.parse(it).text() }
    }

    // Why the months come from here: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
    private val monthlyEventsUrl = "https://www.newcrossinn.com/wp-admin/admin-ajax.php"

    // form() sets the body but not the content type, and admin-ajax fills $_POST from that header
    // alone - without it the action never arrives and WordPress answers 400
    private fun monthlyEvents(month: String, year: String): String =
        client(
            Request(POST, monthlyEventsUrl)
                .header("content-type", "application/x-www-form-urlencoded")
                .form("action", "nci_monthly_events_results")
                .form("month", month)
                .form("year", year)
        ).bodyString()
}
