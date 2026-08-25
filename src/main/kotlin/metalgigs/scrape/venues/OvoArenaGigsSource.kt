package metalgigs.scrape.venues

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.jsonnode.JsonNodeString
import com.ubertob.kondor.json.str
import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.YearMonth

val ovoArena = Venue(VenueId("ovo-arena"), "OVO Arena Wembley")

// Why the calendar rather than the events page: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
class OvoArenaGigsSource(private val client: HttpHandler, private val from: YearMonth = YearMonth.now()) : GigsSource {
    override val venue = ovoArena

    override fun latestGigs(): List<Gig> =
        (0 until monthsAhead).map { from.plusMonths(it.toLong()) }
            .flatMap { eventsIn(it) }
            .filter { it.category == musicCategory }
            .map { event ->
                // e.g. "2026-09-02T18:30:00.0000000" - a local time with no zone, and the gig's own
                // date is the whole of what's wanted from it
                event to GigDate.parse(event.startDateTime.take(10))
            }
            // an afternoon and an evening showing of the same thing are two rows on one date, and one
            // gig as far as the page is concerned - Big Gig 2026 ran 11:45 and 16:45 on 10 October
            .distinctBy { (event, date) -> gigUrl(event, date) }
            .map { (event, date) ->
                Gig(
                    GigId(venue.id, gigUrl(event, date)),
                    titleFrom(event.title),
                    date,
                    posterUrlFrom(gigUrl(event, date), event.imageUrl),
                    fetchDescription(client, GigUrl(event.url), ::eventPageContent),
                )
            }

    private fun eventsIn(month: YearMonth): List<OvoArenaEvent> {
        val url = "https://www.ovoarena.co.uk/events/calendar/${month.year}/${month.monthValue}"
        return JOvoArenaCalendar.fromJson(fetchPage(client, url)).orThrow().events
    }

    // Why the date is in the url: docs/adr/0005-a-gig-is-identified-by-the-url-it-lives-at.md
    private fun gigUrl(event: OvoArenaEvent, date: GigDate) =
        gigUrlFrom("${event.url}#$date", "https://www.ovoarena.co.uk/events/detail/")

    // Everything around this is the venue's own: door times and ticket links above it, then age
    // policy, an AXS ticket-transfer notice, and travel warnings about whatever is on at the stadium
    // next door - none of it about the act, and on a short listing far longer than the copy.
    internal fun eventPageContent(page: Document) = page.select(".event_description").textOrNull()

    private val musicCategory = "3"

    private val monthsAhead = 18
}

private object JOvoArenaCalendar : JAny<OvoArenaCalendar>() {
    private val events by array(JOvoArenaEvent, OvoArenaCalendar::events)
    override fun JsonNodeObject.deserializeOrThrow() = OvoArenaCalendar(+events)
}

private object JOvoArenaEvent : JAny<OvoArenaEvent>() {
    private val Title by str(OvoArenaEvent::title)
    private val URL by str(OvoArenaEvent::url)
    private val StartDateTime by str(OvoArenaEvent::startDateTime)
    private val Category by str(OvoArenaEvent::category)

    // An event with no poster carries `"ImageURL": false`. Kondor reads a missing field and a null
    // one as absent, but a boolean where a string belongs fails the whole month's parse rather than
    // the one event, so it is read as a node: anything but a string is no poster.
    private fun JsonNodeObject.imageUrl() = (_fieldMap["ImageURL"] as? JsonNodeString)?.text.orEmpty()

    override fun JsonNodeObject.deserializeOrThrow() = OvoArenaEvent(
        title = +Title,
        url = +URL,
        imageUrl = imageUrl(),
        startDateTime = +StartDateTime,
        category = +Category,
    )
}

private data class OvoArenaCalendar(val events: List<OvoArenaEvent>)

private data class OvoArenaEvent(
    val title: String,
    val url: String,
    val imageUrl: String,
    val startDateTime: String,
    val category: String,
)
