package metalgigs.scrape.venues

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.JList
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.str
import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

val theGarage = Venue(VenueId("the-garage"), "The Garage")

class TheGarageGigsSource(client: HttpHandler) :
    GigsSource by AltTicketsVenueGigsSource(client, url = "https://www.alttickets.com/venue/london/the-garage", venue = theGarage)

val theGrace = Venue(VenueId("the-grace"), "The Grace")

class TheGraceGigsSource(client: HttpHandler) :
    GigsSource by AltTicketsVenueGigsSource(client, url = "https://www.alttickets.com/venue/london/the-grace", venue = theGrace)

// Why this reading surface: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
internal class AltTicketsVenueGigsSource(
    private val client: HttpHandler,
    private val url: String,
    override val venue: Venue,
) : GigsSource {
    override fun latestGigs(): List<Gig> =
        eventsIn(Jsoup.parse(fetchPage(client, url, browserHeaders), url)).map { event ->
            val gigUrl = gigUrlFrom(event.url, "https://www.alttickets.com/")
            val eventPage = Jsoup.parse(fetchPage(client, gigUrl.value, browserHeaders), gigUrl.value)

            Gig(
                GigId(venue.id, gigUrl),
                titleFrom(event.name),
                // e.g. "2026-11-07T18:00:00+00:00"
                GigDate.parse(event.startDate.take(10)),
                // Why this size: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
                posterUrlFrom(gigUrl, posterIn(eventPage)),
                descriptionFrom(eventPage, gigUrl, ::eventPageContent),
            )
        }

    private val jsonLd = "script[type=application/ld+json]"

    // AltTickets answers OkHttp's own User-Agent with a 403, and did not on 2026-10-20.
    private val browserHeaders = listOf(
        "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    )

    private fun eventsIn(listing: Document): List<AltTicketsEvent> {
        val events = listing.selectFirst(jsonLd)
            ?: error("No JSON-LD on the listing at $url - the venue's page no longer carries it")
        return JList(JAltTicketsEvent).fromJson(events.data()).orThrow()
    }

    private fun posterIn(eventPage: Document): String? =
        eventPage.selectFirst(jsonLd)?.let { JAltTicketsEventPage.fromJson(it.data()).orThrow().image }

    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    internal fun eventPageContent(page: Document): String? {
        val article = page.selectFirst(".information.venue article")
            ?: return if (page.text().contains("This event is not on sale yet")) "" else null
        val copy = article.childNodes()
            .dropWhile { !it.isHeading("Event Information") }
            .drop(1)
            .takeWhile { !it.isHeading("Venue Address") && !it.isBill() }
            .map { it.ownText() }
        val bill = article.select("> div:has(h3:contains(Performing Artist)) > p").eachText()
        return (copy + bill).filter { it.isNotBlank() }.joinToString(" ").replace(pastedTrackingPixel, "").trim()
    }

    // Tom High's copy on 2026-11-04 was a promoter's Meta Pixel snippet pasted in as visible text, and nothing else.
    private val pastedTrackingPixel = Regex("""<!-- Meta Pixel Code -->.*<!-- End Meta Pixel Code -->""")

    private fun Node.isHeading(text: String) = this is Element && tagName() == "h3" && text().startsWith(text)

    private fun Node.isBill() = this is Element && selectFirst("> h3:contains(Performing Artist)") != null

    private fun Node.ownText() = when (this) {
        is Element -> text()
        is TextNode -> text().trim()
        else -> ""
    }
}

private data class AltTicketsEventPage(val image: String?)

private object JAltTicketsEventPage : JAny<AltTicketsEventPage>() {
    private val image by str(AltTicketsEventPage::image)

    override fun JsonNodeObject.deserializeOrThrow() = AltTicketsEventPage(+image)
}

private data class AltTicketsEvent(val name: String, val url: String, val startDate: String)

private object JAltTicketsEvent : JAny<AltTicketsEvent>() {
    private val name by str(AltTicketsEvent::name)
    private val url by str(AltTicketsEvent::url)
    private val startDate by str(AltTicketsEvent::startDate)

    override fun JsonNodeObject.deserializeOrThrow() = AltTicketsEvent(+name, +url, +startDate)
}
