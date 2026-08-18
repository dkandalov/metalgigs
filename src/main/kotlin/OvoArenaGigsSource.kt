import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.jsonnode.JsonNodeString
import com.ubertob.kondor.json.str
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.YearMonth

// The events page renders its cards server-side but filters them in the browser: the "All
// Categories" drop-down carries the category on itself (Sports 1, Music 3, Comedy 4, Performance 5)
// and the cards carry none, so there is nothing in that page's markup to select gigs by. Its query
// string is ignored, and its "More Events" button pages twelve at a time through an endpoint the
// page never names.
//
// The calendar the same site drives its month widget from answers all of that in one request per
// month: /events/calendar/{year}/{month} returns every event that month as JSON, each carrying the
// Category the drop-down filters on. Music is 3, which is what this keeps.
private data class OvoArenaEvent(
    val title: String,
    val url: String,
    val imageUrl: String,
    val startDateTime: String,
    val category: String,
)

private data class OvoArenaCalendar(val events: List<OvoArenaEvent>)

private object JOvoArenaEvent : JAny<OvoArenaEvent>() {
    private val Title by str(OvoArenaEvent::title)
    private val URL by str(OvoArenaEvent::url)
    private val StartDateTime by str(OvoArenaEvent::startDateTime)
    private val Category by str(OvoArenaEvent::category)

    // An event with no poster carries `"ImageURL": false` rather than a url, a null, or nothing at
    // all, which no string converter will take - Kondor reads a missing field and a null one as
    // absent, but a boolean where a string belongs is an error, and it fails the whole month's
    // parse rather than the one event. Read as a node instead, anything but a string is no poster.
    private fun JsonNodeObject.imageUrl() = (_fieldMap["ImageURL"] as? JsonNodeString)?.text.orEmpty()

    override fun JsonNodeObject.deserializeOrThrow() = OvoArenaEvent(
        title = +Title,
        url = +URL,
        imageUrl = imageUrl(),
        startDateTime = +StartDateTime,
        category = +Category,
    )
}

private object JOvoArenaCalendar : JAny<OvoArenaCalendar>() {
    private val events by array(JOvoArenaEvent, OvoArenaCalendar::events)
    override fun JsonNodeObject.deserializeOrThrow() = OvoArenaCalendar(events = +events)
}

val ovoArena = Venue(VenueId("ovo-arena"), "OVO Arena Wembley")

class OvoArenaGigsSource(private val client: HttpHandler, private val from: YearMonth = YearMonth.now()) : GigsSource {
    override val venue = ovoArena

    private val musicCategory = "3"

    // One request per month, so how far to go has to be decided rather than followed. Counting empty
    // months would stop early: the listing read on 2026-08-17 had nothing at all in January 2027 and
    // three gigs in each of February and March. Eighteen covers the year the page can show, with room
    // for an arena booking further ahead than the eight months it was listing then.
    private val monthsAhead = 18

    private fun eventsIn(month: YearMonth): List<OvoArenaEvent> {
        val url = "https://www.ovoarena.co.uk/events/calendar/${month.year}/${month.monthValue}"
        return JOvoArenaCalendar.fromJson(fetchPage(client, url)).orThrow().events
    }

    // Everything around this is the venue's own: door times and ticket links above it, then age
    // policy, an AXS ticket-transfer notice, and travel warnings about whatever is on at the stadium
    // next door - none of it about the act, and on a short listing far longer than the copy.
    internal fun eventPageContent(page: Document) = page.select(".event_description").textOrNull()

    // A run of nights is one event page listed once per night, so the page's own url can't tell them
    // apart - André Rieu's two September nights share one. The date is what separates them, and it's
    // appended to every gig rather than only the ones that need it: adding it only on collision would
    // change the url of a gig already logged the day the venue announces a second night, and it would
    // read as a new gig rather than the one already there. The fragment is inert - the link still
    // opens the page it names.
    private fun gigUrl(event: OvoArenaEvent, date: LocalDate) = "${event.url}#$date"

    override fun latestGigs(): List<Gig> =
        (0 until monthsAhead).map { from.plusMonths(it.toLong()) }
            .flatMap { eventsIn(it) }
            .filter { it.category == musicCategory }
            .map { event ->
                // e.g. "2026-09-02T18:30:00.0000000" - a local time with no zone, and the gig's own
                // date is the whole of what's wanted from it
                event to LocalDate.parse(event.startDateTime.take(10))
            }
            // an afternoon and an evening showing of the same thing are two rows on one date, and one
            // gig as far as the page is concerned - Big Gig 2026 ran 11:45 and 16:45 on 10 October
            .distinctBy { (event, date) -> gigUrl(event, date) }
            .map { (event, date) ->
                Gig(
                    id = GigId(venue.id, gigUrl(event, date)),
                    title = GigTitle(event.title),
                    date = date,
                    imageUrl = PosterUrl(event.imageUrl),
                    description = fetchDescription(client, event.url, ::eventPageContent),
                )
            }
}
