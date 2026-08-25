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
    private val skippableGigs: Set<GigUrl> = emptySet(),
) : GigsSource {
    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select("article.eventlist-event--upcoming")
            .mapNotNull { item ->
                val titleLink = item.select(".eventlist-title-link")
                // a Squarespace events list hangs each event off the listing page's own path, so
                // The Black Heart lists at /events and its gigs sit at /events/2026/9/30/morag-tong
                val gigUrl = gigUrlFrom(titleLink.attr("abs:href"), "$url/")
                gigOrSkipped(gigUrl, skippableGigs) {
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
            }

    // Squarespace's "Events List" block sometimes resolves the thumbnail's `src` eagerly and sometimes
    // leaves it lazy-loaded with only `data-image` set, depending on the site
    private fun Element.squarespaceThumbnailUrl(): String {
        val img = select(".eventlist-column-thumbnail img")
        return img.attr("abs:src").ifBlank { img.attr("abs:data-image") }
    }

    // Why the copy is scoped and re-parsed: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    internal fun eventPageContent(page: Document) =
        page.select(".eventitem-column-content").textOrNull()?.let { Jsoup.parse(it).text() }
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
        skippableGigs = setOf(
            GigUrl("https://www.thefiddlerselbow.co.uk/whos-playing/s-for-sierra-ep-launch-party1992026"),
        ),
    )
