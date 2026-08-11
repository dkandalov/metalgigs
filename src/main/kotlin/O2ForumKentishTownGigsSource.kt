import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.str
import org.http4k.core.HttpHandler
import java.time.OffsetDateTime

// the venue's listing page is a Next.js SPA that renders nothing server-side and paginates
// client-side, but it feeds itself from this plain JSON API, which happily serves every event in
// one request - so we call that directly rather than rendering the page and paging through it
private data class AmgTicket(val ticketUrl: String)
private data class AmgEvent(val name: String, val eventDate: String, val image: String, val tickets: List<AmgTicket>)
private data class AmgSearchResults(val documents: List<AmgEvent>)

private object JAmgTicket : JAny<AmgTicket>() {
    private val ticketUrl by str(AmgTicket::ticketUrl)
    override fun JsonNodeObject.deserializeOrThrow() = AmgTicket(ticketUrl = +ticketUrl)
}

private object JAmgEvent : JAny<AmgEvent>() {
    private val name by str(AmgEvent::name)
    private val eventDate by str(AmgEvent::eventDate)
    private val image by str(AmgEvent::image)
    private val tickets by array(JAmgTicket, AmgEvent::tickets)

    override fun JsonNodeObject.deserializeOrThrow() = AmgEvent(
        name = +name,
        eventDate = +eventDate,
        image = +image,
        tickets = +tickets,
    )
}

private object JAmgSearchResults : JAny<AmgSearchResults>() {
    private val documents by array(JAmgEvent, AmgSearchResults::documents)
    override fun JsonNodeObject.deserializeOrThrow() = AmgSearchResults(documents = +documents)
}

class O2ForumKentishTownGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = "O2 Forum Kentish Town"

    // PageSize is well above the ~90 events actually listed, so everything comes back in one page
    private val url = "https://www.academymusicgroup.com/api/search/events" +
        "?VenueIds=5597&IncludePostponed=true&IncludeCancelled=true" +
        "&Url=%2Fo2forumkentishtown%2Fevents&PageSize=500&Page=1"

    override fun latestGigs(): List<GigEvent> {
        val results = JAmgSearchResults.fromJson(fetchPage(client, url)).orThrow()
        check(results.documents.isNotEmpty()) { "No events returned by $url" }

        return results.documents.map { event ->
            GigEvent.of(
                title = event.name,
                venue = venue,
                // e.g. "2026-08-11T00:00:00Z" - only the date part is meaningful here
                date = OffsetDateTime.parse(event.eventDate).toLocalDate(),
                // no per-gig page on the venue's own site, so the ticketing link identifies the gig -
                // but only up to its query string: one gig lists several tickets (general onsale,
                // presales, partner-branded) whose urls differ purely by marketing params and whose
                // order isn't stable between gigs, so the same gig would otherwise keep changing
                // identity. Everything after "?" is dropped, leaving the ticket platform's own
                // event id, which is stable and still a working link
                url = event.tickets.first().ticketUrl.substringBefore('?'),
                imageUrl = event.image,
            )
        }
    }
}
