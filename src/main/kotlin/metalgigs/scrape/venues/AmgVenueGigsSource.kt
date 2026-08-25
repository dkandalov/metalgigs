package metalgigs.scrape.venues

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.str
import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup

// shared by every Academy Music Group venue; the venue-specific classes below supply the venue and
// AMG's own numeric id(s) for it, more than one where a site lists several rooms together.
// Why the search API rather than the page: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
private class AmgVenueGigsSource(private val client: HttpHandler, vararg amgVenueIds: Int, override val venue: Venue) : GigsSource {
    override fun latestGigs(): List<Gig> {
        val results = JAmgSearchResults.fromJson(fetchPage(client, url)).orThrow()
        check(results.documents.isNotEmpty()) { "No events returned by $url" }

        // Why these are dropped rather than failing: docs/adr/0002-a-source-fails-rather-than-publishing-something-plausible.md
        val ticketless = results.documents.filter { it.ticketUrl == null }
        if (ticketless.isNotEmpty()) println("Skipping ${ticketless.size} $venue gig(s) with no ticket link: ${ticketless.joinToString { it.name }}")

        return results.documents.mapNotNull { event ->
            val ticketUrl = event.ticketUrl ?: return@mapNotNull null
            // Ticketmaster serves the same event under either scheme - the log holds two gigs at
            // http:// among hundreds at https:// - and Gigantic stands in where Ticketmaster has no
            // listing for a show.
            // Why the query string is dropped: docs/adr/0005-a-gig-is-identified-by-the-url-it-lives-at.md
            val gigUrl = gigUrlFrom(
                ticketUrl.substringBefore('?'),
                "https://www.ticketmaster.co.uk/",
                "http://www.ticketmaster.co.uk/",
                "https://www.gigantic.com/",
            )
            Gig(
                GigId(venue.id, gigUrl),
                titleFrom(event.name),
                // e.g. "2026-08-11T00:00:00Z" - only the date part is meaningful here
                GigDate.parse(event.eventDate.substringBefore('T')),
                PosterUrl(event.image.ifBlank { imageFromEventPage(event) }),
                event.description(),
            )
        }
    }

    // A listing's encodedName can lag the page's canonical slug, which the site answers with a 308
    // the client follows.
    // Why the page is asked for the image: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
    private fun imageFromEventPage(event: AmgEvent): String {
        val act = event.lineup.firstOrNull()
            ?: error("No lineup on \"${event.name}\" to build its event page url from, so its poster can't be found")
        val pageUrl = "https://www.academymusicgroup.com/$sitePath/events/${event.encodedName}-tickets-ae${act.id}"
        val src = Jsoup.parse(fetchPage(client, pageUrl), pageUrl).select("img[sizes=100vw]").attr("abs:src")
        check(src.isNotBlank()) { "No hero image on $pageUrl - every event page renders one, its own or AMG's default" }
        return src.substringBefore('?')
    }

    // The event pages live under the site path, which is the venue id with its dashes dropped -
    // and only that: both rooms list there, while an Academy2 event names its own room in the api
    // (o2-academy2-islington), whose slug is no page at all.
    private val sitePath = venue.id.value.replace("-", "")

    // PageSize is well above what any one venue actually lists, so everything comes back in one
    // page - the listing page itself paginates client-side, but the API needn't
    private val url = "https://www.academymusicgroup.com/api/search/events" +
        "?VenueIds=${amgVenueIds.joinToString(",")}&IncludePostponed=true&IncludeCancelled=true&PageSize=500&Page=1"
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

// Neither isVisible nor ticketStatus separates a ticket with a link from one without.
// Why the first ticket that has one: docs/adr/0005-a-gig-is-identified-by-the-url-it-lives-at.md
private val AmgEvent.ticketUrl: String?
    get() = tickets.firstNotNullOfOrNull { it.ticketUrl.ifBlank { null } }

// The copy is html, so it's parsed for its text the way an event page's would be.
private fun AmgEvent.description(): GigDescription =
    GigDescription(
        Jsoup.parse(localizations.firstOrNull()?.description ?: "").text().trim()
            .takeUnless { it.isBlank() || cancellationNotice.containsMatchIn(it) }
            ?: actsAndGenres()
    )

// A cancelled show has its copy replaced by AMG's standard notice, word for word across venues, so
// it stands in for a description without being one - the same thing a blank says, said at length.
// Taken as copy it would also read as one gig's text on another, which is what it is.
private val cancellationNotice = Regex("""^sorry, this show has been cancelled""", RegexOption.IGNORE_CASE)

// The api lists the acts headliner first, so they're left in its order rather than resorted.
// Why acts and genres stand in for copy: docs/adr/0007-a-description-is-the-gigs-own-copy.md
private fun AmgEvent.actsAndGenres(): String =
    listOfNotNull(
        lineup.joinToString(", ") { it.name }.ifBlank { null }?.let { "Lineup: $it." },
        genres.joinToString(", ") { it.name }.ifBlank { null }?.let { "Genre: $it." },
    ).joinToString(" ")

private object JAmgSearchResults : JAny<AmgSearchResults>() {
    private val documents by array(JAmgEvent, AmgSearchResults::documents)
    override fun JsonNodeObject.deserializeOrThrow() = AmgSearchResults(+documents)
}

private object JAmgEvent : JAny<AmgEvent>() {
    private val name by str(AmgEvent::name)
    private val encodedName by str(AmgEvent::encodedName)
    private val eventDate by str(AmgEvent::eventDate)
    private val image by str(AmgEvent::image)
    private val tickets by array(JAmgTicket, AmgEvent::tickets)
    private val localizations by array(JAmgLocalization, AmgEvent::localizations)
    private val genres by array(JAmgGenre, AmgEvent::genres)
    private val lineup by array(JAmgAct, AmgEvent::lineup)

    override fun JsonNodeObject.deserializeOrThrow() = AmgEvent(
        name = +name,
        encodedName = +encodedName,
        eventDate = +eventDate,
        image = +image,
        tickets = +tickets,
        localizations = +localizations,
        genres = +genres,
        lineup = +lineup,
    )
}

private object JAmgTicket : JAny<AmgTicket>() {
    private val ticketUrl by str(AmgTicket::ticketUrl)
    override fun JsonNodeObject.deserializeOrThrow() = AmgTicket(+ticketUrl)
}

// Every event carries exactly one localization, always en-GB, whose description is the promoter's
// copy as html. The sibling mainEventInformation field repeats it word for word wherever there is
// any, so only one of the two is read.
private object JAmgLocalization : JAny<AmgLocalization>() {
    private val description by str(AmgLocalization::description)
    override fun JsonNodeObject.deserializeOrThrow() = AmgLocalization(+description)
}

private object JAmgGenre : JAny<AmgGenre>() {
    private val name by str(AmgGenre::name)
    override fun JsonNodeObject.deserializeOrThrow() = AmgGenre(+name)
}

private object JAmgAct : JAny<AmgAct>() {
    private val id by str(AmgAct::id)
    private val name by str(AmgAct::name)
    override fun JsonNodeObject.deserializeOrThrow() = AmgAct(id = +id, name = +name)
}

private data class AmgSearchResults(val documents: List<AmgEvent>)

private data class AmgEvent(
    val name: String,
    val encodedName: String,
    val eventDate: String,
    val image: String,
    val tickets: List<AmgTicket>,
    val localizations: List<AmgLocalization>,
    val genres: List<AmgGenre>,
    val lineup: List<AmgAct>,
)

private data class AmgTicket(val ticketUrl: String)
private data class AmgLocalization(val description: String)
private data class AmgGenre(val name: String)
private data class AmgAct(val id: String, val name: String)
