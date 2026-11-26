package metalgigs.scrape.venues

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.str
import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

val cartAndHorses = Venue(VenueId("cart-and-horses"), "Cart & Horses")

// Why the calendar and the endpoint behind its More: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
class CartAndHorsesGigsSource(private val client: HttpHandler, private val joinedActs: JoinedActs, private val year: Int) : GigsSource {
    override val venue = cartAndHorses
    override fun latestGigs(): List<Gig> {
        var currentYear = year
        var previousMonth: String? = null

        return calendarEntries().map { entry ->
            val month = entry.selectFirst(".calendar-list__month")!!.text()
            if (month == "Jan" && previousMonth != null && previousMonth != "Jan") currentYear++
            previousMonth = month
            val heading = entry.selectFirst(".calendar-list__title-text")!!
            val gigUrl = gigUrlFrom(heading.attr("abs:href"), "https://www.cartandhorses.london/")
            // A run of nights is dated by its first, and its heading ends on a <small> of its own
            // saying when the last is: "Until 18th December 2026".
            val listedAs = GigTitle(heading.ownText())
            val eventPage = Jsoup.parse(fetchPage(client, gigUrl.value), gigUrl.value)

            Gig(
                GigId(venue.id, gigUrl),
                listedAs,
                GigDate(currentYear, monthsByShortName.getValue(month), entry.selectFirst(".calendar-list__day")!!.text().toInt()),
                // Why the page's image: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
                posterUrlFrom(gigUrl, eventPage.selectFirst("a.hb-single")?.attr("abs:href")),
                descriptionFrom(eventPage, gigUrl, ::eventPageContent),
                lineupHeadedBy(listedAs, joinedActs),
                status = null,
            )
        }
    }

    private fun calendarEntries(): List<Element> {
        val listing = Jsoup.parse(fetchPage(client, url), url)
        val more = listing.selectFirst(".ajax_more_by_month[data-type=events]")
            ?: error("No More control under the calendar at $url - the entries on the page can't be told from the whole calendar")
        val entries = listing.select(".ajax_append_events article.calendar-list").toMutableList()

        var offset = more.attr("data-id").toInt()
        repeat(maxBatches) {
            val batch = batchFrom(offset, more.attr("data-last-date"), listing.body().attr("data-theme-id"))
            if (batch.isEmpty()) return entries
            entries += batch
            offset += batch.size
        }
        error("$venue's calendar was still answering after $maxBatches batches - its More endpoint may no longer end on an empty one")
    }

    private fun batchFrom(offset: Int, lastDate: String, themeId: String): List<Element> {
        val batch = JCalendarBatch.fromJson(fetchPage(client, "${url}next/events/$offset/$lastDate/?theme_id=$themeId&option_id=1")).orThrow()
        return Jsoup.parse(batch.view, url).select("article.calendar-list")
    }

    private val url = "https://www.cartandhorses.london/news-offers-events/"

    // Only to bound a pathological site bug; the real stop is a batch coming back empty. A batch
    // was 20 entries on 2026-11-26, the calendar 42.
    private val maxBatches = 20

    // The Useyourlocal pub-website platform scopes an event page to nothing, so the whole page's
    // text carries the nav and footer - opening times, address, social links - into every gig.
    internal fun eventPageContent(page: Document) = page.select(".page_header, .page_content_inner").textOrNull()
}

private object JCalendarBatch : JAny<CalendarBatch>() {
    private val view by str(CalendarBatch::view)

    override fun JsonNodeObject.deserializeOrThrow() = CalendarBatch(+view)
}

// The entries arrive as one string of html rather than as events.
private data class CalendarBatch(val view: String)
