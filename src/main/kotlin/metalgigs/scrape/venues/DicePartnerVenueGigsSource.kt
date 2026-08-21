package metalgigs.scrape.venues

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.obj
import com.ubertob.kondor.json.str
import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

// shared by every venue listed through the Dice partner API. 229's listing page renders nothing
// itself - it just embeds Dice.fm's "event list" widget (widgets.dice.fm/dice-event-list-widget.js),
// a different Dice surface from the dice.fm venue pages DiceVenueGigsSource reads. The widget's own
// config, inline in the page, names the partner API it calls and the credentials to call it with:
//   DiceEventListWidget.create({"partnerId":"206d7605","apiKey":"9PmJEatQBB8iKSivm8gCbIvKIeU3S4x4MqPoT6Tg","venues":["229"]})
// That apiKey is a public widget key shipped to every browser that loads the page, not a secret -
// found by fetching the widget script and reading where it builds its request (it reads
// RUNTIME_API_URL, appends /api/v2/events, and sends the key as an x-api-key header).
//
// The key is not scoped to 229: the same one answers for any venue named in the filter, which is
// what lets the Signature Brew taprooms below share it. That coupling is the risk in doing so - if
// Dice ever scopes or rotates the key, every venue here stops listing at once, not just 229's.
private class DicePartnerVenueGigsSource(
    private val client: HttpHandler,
    private val venueFilter: String,
    override val venue: Venue,
) : GigsSource {
    override fun latestGigs(): List<Gig> {
        val events = mutableListOf<DicePartnerEvent>()
        var pageUrl: String? = firstPageUrl

        while (pageUrl != null) {
            val response = JDicePartnerEventsResponse.fromJson(fetchPage(client, pageUrl, listOf("x-api-key" to apiKey))).orThrow()
            events += response.data
            // the widget itself does the same reconstruction rather than fetching links.next
            // directly - that url is served under a different host (events-api.dice.fm) than the
            // partner endpoint it authenticates against, so only the query string is reused
            pageUrl = response.links.next?.let { next -> "$baseUrl?${next.substringAfter('?')}" }
        }
        check(events.isNotEmpty()) { "No events returned by $firstPageUrl" }

        return events.map { event ->
            // none of these venues gives a gig its own page on its own site, so a stable url built
            // from the event's own perm_name identifies it instead - not the short ticketing link
            // (link.dice.fm/...), which is opaque and reused across unrelated calls to the API
            val gigUrl = GigUrl("https://dice.fm/event/${event.permName}")
            Gig(
                GigId(venue.id, gigUrl),
                titleFrom(event.name),
                // e.g. "2026-08-14T17:30:00Z" - only the date part is meaningful here
                GigDate.parse(event.date.substringBefore('T')),
                posterUrlFrom(gigUrl, event.images.firstOrNull()),
                GigDescription(event.rawDescription),
            )
        }
    }

    private val apiKey = "9PmJEatQBB8iKSivm8gCbIvKIeU3S4x4MqPoT6Tg"
    private val baseUrl = "https://partners-endpoint.dice.fm/api/v2/events"

    // The filter matches the venue's name, not any id: passing the numeric id a dice.fm venue page
    // carries (2427 for Signature Brew Haggerston) returns an empty listing rather than an error.
    //
    // page[size]=200 already brings back the whole listing in one request for every venue here
    // today - the largest, 229's, is 77 events, with the response's own links.next confirming
    // there's nothing more - but that's a fact about today's listings, not a guarantee, so this
    // still follows links.next like a real "load more" would rather than trusting the page size to
    // stay big enough forever
    private val firstPageUrl =
        "$baseUrl?page%5Bsize%5D=200&types=linkout,event&filter%5Bvenues%5D%5B%5D=${URLEncoder.encode(venueFilter, UTF_8)}"
}

val twoTwoNine = Venue(VenueId("229"), "229")

class TwoTwoNineGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "229", venue = twoTwoNine)

val signatureBrewHaggerston = Venue(VenueId("signature-brew-haggerston"), "Signature Brew Haggerston")

class SignatureBrewHaggerstonGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "Signature Brew Haggerston", venue = signatureBrewHaggerston)

val signatureBrewBlackhorseRoad = Venue(VenueId("signature-brew-blackhorse-road"), "Signature Brew Blackhorse Road")

class SignatureBrewBlackhorseRoadGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "Signature Brew Blackhorse Road", venue = signatureBrewBlackhorseRoad)

private object JDicePartnerEventsResponse : JAny<DicePartnerEventsResponse>() {
    private val data by array(JDicePartnerEvent, DicePartnerEventsResponse::data)
    private val links by obj(JDicePartnerLinks, DicePartnerEventsResponse::links)
    override fun JsonNodeObject.deserializeOrThrow() = DicePartnerEventsResponse(+data, +links)
}

private object JDicePartnerEvent : JAny<DicePartnerEvent>() {
    private val name by str(DicePartnerEvent::name)
    private val perm_name by str(DicePartnerEvent::permName)
    private val date by str(DicePartnerEvent::date)
    private val images by array(DicePartnerEvent::images)
    private val raw_description by str(DicePartnerEvent::rawDescription)

    override fun JsonNodeObject.deserializeOrThrow() = DicePartnerEvent(
        name = +name,
        permName = +perm_name,
        +date,
        images = +images,
        rawDescription = +raw_description,
    )
}

private object JDicePartnerLinks : JAny<DicePartnerLinks>() {
    private val next by str(DicePartnerLinks::next)
    override fun JsonNodeObject.deserializeOrThrow() = DicePartnerLinks(+next)
}

private data class DicePartnerEventsResponse(val data: List<DicePartnerEvent>, val links: DicePartnerLinks)

// rawDescription is the venue's own copy, the same text the dice.fm event page shows. The response
// carries a second `description` too, which appends Dice's own footer ("Presented by ...", "This is
// an 18+ event"), so it is the wrong one to keep - and having either means no per-gig page fetch.
private data class DicePartnerEvent(
    val name: String,
    val permName: String,
    val date: String,
    val images: List<String>,
    val rawDescription: String,
)

private data class DicePartnerLinks(val next: String?)
