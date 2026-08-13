import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.obj
import com.ubertob.kondor.json.str
import org.http4k.core.HttpHandler
import java.time.OffsetDateTime

// 229's listing page renders nothing itself - it just embeds Dice.fm's "event list" widget
// (widgets.dice.fm/dice-event-list-widget.js), a different Dice surface from the dice.fm venue
// pages DiceVenueGigsSource reads. The widget's own config, inline in the page, names the partner
// API it calls and the credentials to call it with:
//   DiceEventListWidget.create({"partnerId":"206d7605","apiKey":"9PmJEatQBB8iKSivm8gCbIvKIeU3S4x4MqPoT6Tg","venues":["229"]})
// That apiKey is a public widget key shipped to every browser that loads the page, not a secret -
// found by fetching the widget script and reading where it builds its request (it reads
// RUNTIME_API_URL, appends /api/v2/events, and sends the key as an x-api-key header)
private data class DicePartnerEvent(val name: String, val permName: String, val date: String, val images: List<String>)
private data class DicePartnerLinks(val next: String?)
private data class DicePartnerEventsResponse(val data: List<DicePartnerEvent>, val links: DicePartnerLinks)

private object JDicePartnerEvent : JAny<DicePartnerEvent>() {
    private val name by str(DicePartnerEvent::name)
    private val perm_name by str(DicePartnerEvent::permName)
    private val date by str(DicePartnerEvent::date)
    private val images by array(DicePartnerEvent::images)

    override fun JsonNodeObject.deserializeOrThrow() = DicePartnerEvent(
        name = +name,
        permName = +perm_name,
        date = +date,
        images = +images,
    )
}

private object JDicePartnerLinks : JAny<DicePartnerLinks>() {
    private val next by str(DicePartnerLinks::next)
    override fun JsonNodeObject.deserializeOrThrow() = DicePartnerLinks(next = +next)
}

private object JDicePartnerEventsResponse : JAny<DicePartnerEventsResponse>() {
    private val data by array(JDicePartnerEvent, DicePartnerEventsResponse::data)
    private val links by obj(JDicePartnerLinks, DicePartnerEventsResponse::links)
    override fun JsonNodeObject.deserializeOrThrow() = DicePartnerEventsResponse(data = +data, links = +links)
}

class TwoTwoNineGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = Venue("229")

    private val apiKey = "9PmJEatQBB8iKSivm8gCbIvKIeU3S4x4MqPoT6Tg"
    private val baseUrl = "https://partners-endpoint.dice.fm/api/v2/events"

    // page[size]=200 already brings back the whole listing in one request today (measured: 75
    // events, with the response's own links.next confirming there's nothing more) - but that's a
    // fact about today's listing, not a guarantee, so this still follows links.next like a real
    // "load more" would rather than trusting the page size to stay big enough forever
    private val firstPageUrl = "$baseUrl?page%5Bsize%5D=200&types=linkout,event&filter%5Bvenues%5D%5B%5D=229"

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
            Gig(
                // no per-gig page on 229's own site, so the same identity DiceVenueGigsSource uses
                // for dice.fm venue pages applies here too - a stable url built from the event's
                // own perm_name, not the short ticketing link (link.dice.fm/...), which is opaque
                // and reused across unrelated calls to the API
                id = GigId(venue, "https://dice.fm/event/${event.permName}"),
                title = event.name,
                // e.g. "2026-08-14T17:30:00Z" - only the date part is meaningful here
                date = OffsetDateTime.parse(event.date).toLocalDate(),
                imageUrl = event.images.firstOrNull() ?: "",
            )
        }
    }
}
