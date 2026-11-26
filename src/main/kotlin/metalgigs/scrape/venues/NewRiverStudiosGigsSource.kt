package metalgigs.scrape.venues

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.jsonnode.JsonNodeString
import com.ubertob.kondor.json.num
import com.ubertob.kondor.json.str
import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.LocalDate

val newRiverStudios = Venue(VenueId("new-river-studios"), "New River Studios")

// The calendar carries film matinees, a night market and workshops beside its gigs with no category
// set on any event, so everything is taken and left to the classifier, as at Troxy.
// Why the events api rather than the page: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
class NewRiverStudiosGigsSource(private val client: HttpHandler, private val joinedActs: JoinedActs) : GigsSource {
    override val venue = newRiverStudios

    override fun latestGigs(): List<Gig> = eventsListed().map { event ->
        val gigUrl = gigUrlFrom(event.url, "https://newriverstudios.com/event/")
        // A title arrives with its entities still in it: "Baba Yaga&#8217;s Hut presents: FRANCE".
        val listedAs = titleFrom(Parser.unescapeEntities(event.title, false))

        Gig(
            GigId(venue.id, gigUrl),
            listedAs,
            // "2026-11-27 19:30:00", the night as the venue typed it.
            GigDate(LocalDate.parse(event.startDate.substringBefore(' '))),
            posterUrlFrom(gigUrl, event.imageUrl),
            GigDescription(Jsoup.parse(event.description).text()),
            // Why a heading is a lineup: docs/adr/0015-a-lineup-is-the-acts-a-source-names-held-apart-from-the-title.md
            lineupHeadedBy(listedAs, joinedActs, commaLists = true),
            status = null,
        )
    }

    private fun eventsListed(): List<TribeEvent> {
        val first = batch(1)
        check(first.totalPages <= maxPages) { "$venue's calendar says it runs to ${first.totalPages} pages - its paging may no longer mean what it did" }
        val events = first.events + (2..first.totalPages).flatMap { batch(it).events }

        check(events.size == first.total) { "Read ${events.size} of the ${first.total} events $venue says it lists - its paging may no longer be followed to the end" }
        return events
    }

    private fun batch(page: Int): TribeEvents =
        JTribeEvents.fromJson(fetchPage(client, "https://newriverstudios.com/wp-json/tribe/events/v1/events?per_page=$pageSize&page=$page")).orThrow()

    // Asked for 51 or 100 a page the api still answers 50, without saying so.
    private val pageSize = 50

    // Only to bound a pathological site bug; the real stop is the last page the api names. The
    // calendar held 11 events on 2026-11-26, a page's worth being 50.
    private val maxPages = 10
}

private object JTribeEvents : JAny<TribeEvents>() {
    private val events by array(JTribeEvent, TribeEvents::events)
    private val total by num(TribeEvents::total)
    private val total_pages by num(TribeEvents::totalPages)

    override fun JsonNodeObject.deserializeOrThrow() = TribeEvents(+events, +total, +total_pages)
}

private object JTribeEvent : JAny<TribeEvent>() {
    private val url by str(TribeEvent::url)
    private val title by str(TribeEvent::title)
    private val start_date by str(TribeEvent::startDate)
    private val description by str(TribeEvent::description)

    // Read as a node, so an event with no image is one posterUrlFrom names rather than a batch
    // Kondor fails whole. All 208 events read on 2026-11-26, back to June 2025, carried one.
    private fun JsonNodeObject.imageUrl() = ((_fieldMap["image"] as? JsonNodeObject)?._fieldMap?.get("url") as? JsonNodeString)?.text

    override fun JsonNodeObject.deserializeOrThrow() = TribeEvent(+url, +title, +start_date, +description, imageUrl())
}

private data class TribeEvents(val events: List<TribeEvent>, val total: Int, val totalPages: Int)

// `venue` is left unread: it is an object on some events and an empty list on the rest.
private data class TribeEvent(val url: String, val title: String, val startDate: String, val description: String, val imageUrl: String?)
