package metalgigs.scrape.venues

import com.ubertob.kondor.json.jsonnode.JsonNodeArray
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
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
                val listedAs = GigTitle(item.select("h3.nci-event-name").text())
                val eventPage = Jsoup.parse(fetchPage(client, gigUrl.value), gigUrl.value)
                Gig(
                    GigId(venue.id, gigUrl),
                    titleWithLineup(listedAs, performersOn(eventPage)) ?: listedAs,
                    GigDate(year.toInt(), monthsByShortName.getValue(month), day.toInt()),
                    posterUrlFrom(gigUrl, item.select("img").attr("abs:src")),
                    descriptionFrom(eventPage, gigUrl, ::eventPageContent),
                )
            }
    }

    private val url = "https://www.newcrossinn.com/gigs/"

    private val datePattern = Regex("""(\d{2}) (\w{3}) (\d{4})""")

    // pit.live names each act in the event's JSON-LD, in no fixed order - Brakelight's lists a
    // support first. A gig billing one act gives it as an object rather than a one-act array, 15
    // of the 104 listed on 2026-11-08, and that act is the gig's own name.
    internal fun performersOn(page: Document): List<String> {
        val event = page.select("script[type=application/ld+json]")
            .map { parseJsonNode(it.data()).orThrow() }
            .filterIsInstance<JsonNodeObject>()
            .firstOrNull { "performer" in it._fieldMap }
            ?: return emptyList()
        val performers = when (val performer = event._fieldMap.getValue("performer")) {
            is JsonNodeArray -> performer.elements.toList()
            else -> listOf(performer)
        }
        return performers.mapNotNull { ((it as? JsonNodeObject)?._fieldMap?.get("name") as? JsonNodeString)?.text }
    }

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
