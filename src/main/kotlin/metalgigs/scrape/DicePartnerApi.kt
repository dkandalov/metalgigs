package metalgigs.scrape

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.obj
import com.ubertob.kondor.json.str
import metalgigs.GigDate
import org.http4k.core.HttpHandler
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.time.ZoneId

// Why the partner API and its public key: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
internal class DicePartnerApi(private val client: HttpHandler) {
    fun eventsAt(venueName: String): List<DicePartnerEvent> {
        val events = mutableListOf<DicePartnerEvent>()
        var pageUrl: String? = firstPageUrl(venueName)

        while (pageUrl != null) {
            val response = JDicePartnerEventsResponse.fromJson(fetchPage(client, pageUrl, listOf("x-api-key" to apiKey))).orThrow()
            events += response.data
            // the widget itself does the same reconstruction rather than fetching links.next
            // directly - that url is served under a different host (events-api.dice.fm) than the
            // partner endpoint it authenticates against, so only the query string is reused
            pageUrl = response.links.next?.let { next -> "$baseUrl?${next.substringAfter('?')}" }
        }
        return events
    }

    private val apiKey = "9PmJEatQBB8iKSivm8gCbIvKIeU3S4x4MqPoT6Tg"
    private val baseUrl = "https://partners-endpoint.dice.fm/api/v2/events"

    // The filter matches the venue's name as Dice writes it, not any id: passing the numeric id a
    // dice.fm venue page carries (2427 for Signature Brew Haggerston) returns an empty listing rather
    // than an error, and so does a name only we use for it ("Blondies Bar" for Dice's "Blondies").
    private fun firstPageUrl(venueName: String) =
        "$baseUrl?page%5Bsize%5D=200&types=linkout,event&filter%5Bvenues%5D%5B%5D=${URLEncoder.encode(venueName, UTF_8)}"
}

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
    private val status by str(DicePartnerEvent::status)
    private val artists by array(DicePartnerEvent::artists)

    override fun JsonNodeObject.deserializeOrThrow() = DicePartnerEvent(
        name = +name,
        permName = +perm_name,
        +date,
        timezone = +timezone,
        images = +images,
        rawDescription = +raw_description,
        status = +status,
        artists = +artists,
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
internal data class DicePartnerEvent(
    val name: String,
    val permName: String,
    val date: String,
    val timezone: String,
    val images: List<String>,
    val rawDescription: String,
    val status: String,
    val artists: List<String>,
) {
    // The date is stamped in UTC however late the gig is - "2026-08-18T17:00:00Z" - so a door after
    // midnight in London is a day early until it's read back in the venue's own zone.
    val startsOn: GigDate get() = GigDate(Instant.parse(date).atZone(ZoneId.of(timezone)).toLocalDate())
}

private data class DicePartnerLinks(val next: String?)
