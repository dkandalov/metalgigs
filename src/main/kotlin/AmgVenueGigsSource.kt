import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.str
import org.http4k.core.HttpHandler
import java.time.OffsetDateTime

// AMG's venue listing pages are Next.js SPAs that render nothing server-side and paginate
// client-side, but they feed themselves from this plain JSON API, which happily serves every event
// in one request - so we call that directly rather than rendering a page and paging through it
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

// shared by every Academy Music Group venue; the venue-specific classes below just supply the
// venue and AMG's own numeric id(s) for it (as seen in the API's own venue objects). More than one
// id where a site lists several rooms at the same venue together, as its own listing page does
class AmgVenueGigsSource(private val client: HttpHandler, vararg amgVenueIds: Int, override val venue: Venue) : GigsSource {
    // PageSize is well above what any one venue actually lists, so everything comes back in one
    // page - the listing page itself paginates client-side, but the API needn't
    private val url = "https://www.academymusicgroup.com/api/search/events" +
        "?VenueIds=${amgVenueIds.joinToString(",")}&IncludePostponed=true&IncludeCancelled=true&PageSize=500&Page=1"

    override fun latestGigs(): List<Gig> {
        val results = JAmgSearchResults.fromJson(fetchPage(client, url)).orThrow()
        check(results.documents.isNotEmpty()) { "No events returned by $url" }

        // an event whose ticket sales have closed (typically one happening today) is listed with no
        // tickets at all, leaving it with neither a stable identity nor a link worth rendering, so
        // it's dropped rather than failing the venue's whole scrape over a normal end-of-life state
        val (ticketed, ticketless) = results.documents.partition { it.tickets.isNotEmpty() }
        if (ticketless.isNotEmpty()) println("Skipping ${ticketless.size} $venue gig(s) with no ticket link: ${ticketless.joinToString { it.name }}")

        return ticketed.map { event ->
            // no per-gig page on the venue's own site, so the ticketing link identifies the gig - but
            // only up to its query string: one gig lists several tickets (general onsale, presales,
            // partner-branded) whose urls differ purely by marketing params and whose order isn't
            // stable between gigs, so the same gig would otherwise keep changing identity. Everything
            // after "?" is dropped, leaving the ticket platform's own event id, which is stable and
            // still a working link
            val gigUrl = event.tickets.first().ticketUrl.substringBefore('?')
            Gig(
                id = GigId(venue.id, gigUrl),
                title = event.name,
                // e.g. "2026-08-11T00:00:00Z" - only the date part is meaningful here
                date = OffsetDateTime.parse(event.eventDate).toLocalDate(),
                imageUrl = event.image,
                description = fetchDescription(client, gigUrl) { page -> page.text() },
            )
        }
    }
}

val o2ForumKentishTown = Venue(VenueId("o2-forum-kentish-town"), "O2 Forum Kentish Town")

class O2ForumKentishTownGigsSource(client: HttpHandler) :
    GigsSource by AmgVenueGigsSource(client, 5597, venue = o2ForumKentishTown)

val o2AcademyBrixton = Venue(VenueId("o2-academy-brixton"), "O2 Academy Brixton")

class O2AcademyBrixtonGigsSource(client: HttpHandler) :
    GigsSource by AmgVenueGigsSource(client, 3919, venue = o2AcademyBrixton)

val o2AcademyIslington = Venue(VenueId("o2-academy-islington"), "O2 Academy Islington")

// its listing page covers both the main room and the smaller "Academy2" upstairs (which has no
// listing page of its own), so both are scraped together under the one venue name, exactly as the
// site itself presents them
class O2AcademyIslingtonGigsSource(client: HttpHandler) :
    GigsSource by AmgVenueGigsSource(client, 4361, 4258, venue = o2AcademyIslington)

val o2ShepherdsBushEmpire = Venue(VenueId("o2-shepherds-bush-empire"), "O2 Shepherd's Bush Empire")

class O2ShepherdsBushEmpireGigsSource(client: HttpHandler) :
    GigsSource by AmgVenueGigsSource(client, 4051, venue = o2ShepherdsBushEmpire)
