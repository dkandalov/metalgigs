package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// most Squarespace venues write a gig's blurb on its own event page, but The Fiddler's Elbow leaves
// every one of those empty and puts the whole thing in the listing's excerpt instead - which also
// means one request for the listing rather than one more per gig
internal enum class SquarespaceDescription { EventPage, ListingExcerpt }

// shared by every Squarespace "Events List" venue page
internal class SquarespaceEventsGigsSource(
    private val client: HttpHandler,
    private val url: String,
    override val venue: Venue,
    private val descriptionFrom: SquarespaceDescription = SquarespaceDescription.EventPage,
) : GigsSource {
    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select("article.eventlist-event--upcoming")
            .map { item ->
                val titleLink = item.select(".eventlist-title-link")
                val gigUrl = titleLink.attr("abs:href")
                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(titleLink.text()),
                    GigDate.parse(item.select("time.event-date").first()!!.attr("datetime")),
                    posterUrlFrom(gigUrl, item.squarespaceThumbnailUrl()),
                    when (descriptionFrom) {
                        SquarespaceDescription.EventPage -> fetchDescription(client, gigUrl, ::eventPageContent)
                        SquarespaceDescription.ListingExcerpt -> GigDescription(item.select(".eventlist-excerpt").text())
                    },
                )
            }

    // Squarespace's "Events List" block sometimes resolves the thumbnail's `src` eagerly and sometimes
    // leaves it lazy-loaded with only `data-image` set, depending on the site
    private fun Element.squarespaceThumbnailUrl(): String {
        val img = select(".eventlist-column-thumbnail img")
        return img.attr("abs:src").ifBlank { img.attr("abs:data-image") }
    }

    // article.eventitem also holds the template's own event metadata - the date in both 12- and
    // 24-hour form, the venue's postal address, Google Calendar and ICS links - which is longer
    // than some gigs' actual blurb. The title isn't in here either, but the classifier is given it
    // separately.
    internal fun eventPageContent(page: Document) = page.select(".eventitem-column-content").textOrNull()
}

val theBlackHeart = Venue(VenueId("black-heart"), "The Black Heart")

class TheBlackHeartGigsSource(client: HttpHandler) :
    GigsSource by SquarespaceEventsGigsSource(client, url = "https://www.ourblackheart.com/events", venue = theBlackHeart)

val theDome = Venue(VenueId("dome"), "The Dome")

class DomeLondonGigsSource(client: HttpHandler) :
    GigsSource by SquarespaceEventsGigsSource(client, url = "https://www.domelondon.co.uk/whatson", venue = theDome)

val fiddlersElbow = Venue(VenueId("fiddlers-elbow"), "The Fiddler's Elbow")

class FiddlersElbowGigsSource(client: HttpHandler) :
    GigsSource by SquarespaceEventsGigsSource(
        client,
        url = "https://www.thefiddlerselbow.co.uk/whos-playing",
        venue = fiddlersElbow,
        descriptionFrom = SquarespaceDescription.ListingExcerpt,
    )
