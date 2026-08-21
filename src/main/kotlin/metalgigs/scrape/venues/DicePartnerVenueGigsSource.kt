package metalgigs.scrape.venues

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.obj
import com.ubertob.kondor.json.str
import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.time.ZoneId

// shared by every venue listed through Dice. 229's listing page renders nothing itself - it just
// embeds Dice.fm's "event list" widget (widgets.dice.fm/dice-event-list-widget.js), a different Dice
// surface from the dice.fm venue pages. The widget's own config, inline in the page, names the
// partner API it calls and the credentials to call it with:
//   DiceEventListWidget.create({"partnerId":"206d7605","apiKey":"9PmJEatQBB8iKSivm8gCbIvKIeU3S4x4MqPoT6Tg","venues":["229"]})
// That apiKey is a public widget key shipped to every browser that loads the page, not a secret -
// found by fetching the widget script and reading where it builds its request (it reads
// RUNTIME_API_URL, appends /api/v2/events, and sends the key as an x-api-key header).
//
// The key is not scoped to 229: the same one answers for any venue named in the filter, which is what
// lets the venues below share it. That coupling is the risk in doing so - if Dice ever scopes or
// rotates the key, every venue here stops listing at once, not just 229's.
//
// It is also the only Dice surface that says where a gig lives: dice.fm's own venue pages list an
// event without a perm_name, leaving nothing on them to build a url from. It carries the venue's
// description inline besides, so no venue here makes a per-gig request for one.
private class DicePartnerVenueGigsSource(
    // A gig's url is the one dice.fm redirects to rather than the one this API lists, so this has to
    // be a client that hands a redirect back rather than following it - see gigUrlFor.
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
            val gigUrl = gigUrlFor(event.permName)
            Gig(
                GigId(venue.id, gigUrl),
                titleFrom(event.name),
                // e.g. "2026-08-18T17:00:00Z" - stamped in UTC however late the gig is, so a door
                // after midnight in London is a day early until it's read back in the venue's own zone
                GigDate(Instant.parse(event.date).atZone(ZoneId.of(event.timezone)).toLocalDate()),
                posterUrlFrom(gigUrl, event.images.firstOrNull()),
                GigDescription(event.rawDescription),
            )
        }
    }

    // None of these venues gives a gig a page of its own, so a gig lives at its dice.fm event page -
    // not at the short ticketing link (link.dice.fm/...) this API also carries, which is opaque and
    // reused across unrelated calls to it.
    //
    // dice.fm serves that page under a perm_name prefixed with a short code of its own
    // (2wqb7p-its-never-over-...) and answers the bare perm_name this API returns with a 308 to it.
    // No Dice API hands the prefix out, so the redirect is the only thing that says where a gig
    // lives - and a gig is identified by that, so taking the listed perm_name instead would move
    // every gig at every venue here to a url dice.fm doesn't serve.
    private fun gigUrlFor(permName: String): GigUrl {
        val listed = eventUrl + permName
        val response = client(Request(GET, listed).header("User-Agent", browserUserAgent))
        return when {
            response.status.redirection -> {
                val location = checkNotNull(response.header("location")) { "dice.fm redirected $listed without saying where to" }
                check(location.startsWith(eventUrl)) { "dice.fm redirected $listed to $location, which isn't one of its event pages" }
                GigUrl(location)
            }
            // a perm_name dice.fm serves as it stands, unprefixed
            response.status.successful -> GigUrl(listed)
            else -> error("Failed to resolve $listed: ${response.status}")
        }
    }

    private val eventUrl = "https://dice.fm/event/"
    private val apiKey = "9PmJEatQBB8iKSivm8gCbIvKIeU3S4x4MqPoT6Tg"
    private val baseUrl = "https://partners-endpoint.dice.fm/api/v2/events"

    // dice.fm answers a request without a browser-like User-Agent with a 403 rather than the redirect
    private val browserUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // The filter matches the venue's name as Dice writes it, not any id: passing the numeric id a
    // dice.fm venue page carries (2427 for Signature Brew Haggerston) returns an empty listing rather
    // than an error, and so does a name only we use for it ("Blondies Bar" for Dice's "Blondies").
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

val blondiesBreweryTaproom = Venue(VenueId("blondies-taproom"), "Blondies Brewery Taproom")

class BlondiesBreweryTaproomGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "Blondies Brewery", venue = blondiesBreweryTaproom)

val blondiesBar = Venue(VenueId("blondies-bar"), "Blondies Bar")

class BlondiesBarGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "Blondies", venue = blondiesBar)

val helgis = Venue(VenueId("helgis"), "Helgi's")

class HelgisGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "Helgi's", venue = helgis)

val barfly = Venue(VenueId("barfly"), "Barfly")

class BarflyGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "Barfly Camden", venue = barfly)

private object JDicePartnerEventsResponse : JAny<DicePartnerEventsResponse>() {
    private val data by array(JDicePartnerEvent, DicePartnerEventsResponse::data)
    private val links by obj(JDicePartnerLinks, DicePartnerEventsResponse::links)
    override fun JsonNodeObject.deserializeOrThrow() = DicePartnerEventsResponse(+data, +links)
}

private object JDicePartnerEvent : JAny<DicePartnerEvent>() {
    private val name by str(DicePartnerEvent::name)
    private val perm_name by str(DicePartnerEvent::permName)
    private val date by str(DicePartnerEvent::date)
    private val timezone by str(DicePartnerEvent::timezone)
    private val images by array(DicePartnerEvent::images)
    private val raw_description by str(DicePartnerEvent::rawDescription)

    override fun JsonNodeObject.deserializeOrThrow() = DicePartnerEvent(
        name = +name,
        permName = +perm_name,
        +date,
        timezone = +timezone,
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
    val timezone: String,
    val images: List<String>,
    val rawDescription: String,
)

private data class DicePartnerLinks(val next: String?)
