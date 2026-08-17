import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.str
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import java.time.OffsetDateTime

// AMG's venue listing pages are Next.js SPAs that render nothing server-side and paginate
// client-side, but they feed themselves from this plain JSON API, which happily serves every event
// in one request - so we call that directly rather than rendering a page and paging through it.
// It carries the promoter's copy too, so a venue's whole listing is this one request.
private data class AmgTicket(val ticketUrl: String)
private data class AmgLocalization(val description: String)
private data class AmgGenre(val name: String)
private data class AmgAct(val name: String)
private data class AmgEvent(
    val name: String,
    val eventDate: String,
    val image: String,
    val tickets: List<AmgTicket>,
    val localizations: List<AmgLocalization>,
    val genres: List<AmgGenre>,
    val lineup: List<AmgAct>,
)

private data class AmgSearchResults(val documents: List<AmgEvent>)

private object JAmgTicket : JAny<AmgTicket>() {
    private val ticketUrl by str(AmgTicket::ticketUrl)
    override fun JsonNodeObject.deserializeOrThrow() = AmgTicket(ticketUrl = +ticketUrl)
}

// Every event carries exactly one localization, always en-GB, whose description is the promoter's
// copy as html. The sibling mainEventInformation field repeats it word for word wherever there is
// any, so only one of the two is read.
private object JAmgLocalization : JAny<AmgLocalization>() {
    private val description by str(AmgLocalization::description)
    override fun JsonNodeObject.deserializeOrThrow() = AmgLocalization(description = +description)
}

private object JAmgGenre : JAny<AmgGenre>() {
    private val name by str(AmgGenre::name)
    override fun JsonNodeObject.deserializeOrThrow() = AmgGenre(name = +name)
}

private object JAmgAct : JAny<AmgAct>() {
    private val name by str(AmgAct::name)
    override fun JsonNodeObject.deserializeOrThrow() = AmgAct(name = +name)
}

private object JAmgEvent : JAny<AmgEvent>() {
    private val name by str(AmgEvent::name)
    private val eventDate by str(AmgEvent::eventDate)
    private val image by str(AmgEvent::image)
    private val tickets by array(JAmgTicket, AmgEvent::tickets)
    private val localizations by array(JAmgLocalization, AmgEvent::localizations)
    private val genres by array(JAmgGenre, AmgEvent::genres)
    private val lineup by array(JAmgAct, AmgEvent::lineup)

    override fun JsonNodeObject.deserializeOrThrow() = AmgEvent(
        name = +name,
        eventDate = +eventDate,
        image = +image,
        tickets = +tickets,
        localizations = +localizations,
        genres = +genres,
        lineup = +lineup,
    )
}

private object JAmgSearchResults : JAny<AmgSearchResults>() {
    private val documents by array(JAmgEvent, AmgSearchResults::documents)
    override fun JsonNodeObject.deserializeOrThrow() = AmgSearchResults(documents = +documents)
}

// About one event in ten has no copy written for it - 32 of the 334 these four venues listed as of
// 2026-08-17 - but every one of those still names its acts and its genres, which is what a
// description is for here: nothing renders it, it only tells the classifier what kind of gig this
// is. The api lists the acts headliner first, so they're left in its order rather than resorted.
private fun AmgEvent.actsAndGenres(): String =
    listOfNotNull(
        lineup.joinToString(", ") { it.name }.ifBlank { null }?.let { "Lineup: $it." },
        genres.joinToString(", ") { it.name }.ifBlank { null }?.let { "Genre: $it." },
    ).joinToString(" ")

// A cancelled show has its copy replaced by AMG's standard notice, word for word across venues, so
// it stands in for a description without being one - the same thing a blank says, said at length.
// Taken as copy it would also read as one gig's text on another, which is what it is.
private val cancellationNotice = Regex("""^sorry, this show has been cancelled""", RegexOption.IGNORE_CASE)

// The copy is html, so it's parsed for its text the way an event page's would be.
private fun AmgEvent.description(): String =
    Jsoup.parse(localizations.firstOrNull()?.description ?: "").text().trim()
        .takeUnless { it.isBlank() || cancellationNotice.containsMatchIn(it) }
        ?: actsAndGenres()

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
                title = GigTitle(event.name),
                // e.g. "2026-08-11T00:00:00Z" - only the date part is meaningful here
                date = OffsetDateTime.parse(event.eventDate).toLocalDate(),
                imageUrl = event.image,
                description = event.description(),
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
