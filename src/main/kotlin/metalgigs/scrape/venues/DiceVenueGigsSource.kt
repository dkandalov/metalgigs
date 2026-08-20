package metalgigs.scrape.venues

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNode
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.jsonnode.JsonNodeString
import com.ubertob.kondor.json.jsonnode.parseJsonNode
import com.ubertob.kondor.json.obj
import com.ubertob.kondor.json.str
import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.OffsetDateTime

// shared by every dice.fm venue page. dice.fm renders those pages client-side (Next.js), but embeds
// the full event list as JSON in a <script id="__NEXT_DATA__"> tag, so we parse that directly
// instead of the rendered DOM
private class DiceVenueGigsSource(private val client: HttpHandler, private val url: String, override val venue: Venue) : GigsSource {
    override fun latestGigs(): List<Gig> {
        val page = Jsoup.parse(fetchPage(client, url, listOf("User-Agent" to browserUserAgent)), url)
        val nextData = page.select("script#__NEXT_DATA__").first()
            ?: error("Could not find __NEXT_DATA__ on $url")
        val events = JDiceNextData.fromJson(nextData.data()).orThrow().props.pageProps.profile.sections.flatMap { it.events }

        return events.map { event ->
            val gigUrl = "https://dice.fm/event/${event.permName}"
            Gig(
                GigId(venue.id, gigUrl),
                GigTitle(event.name),
                OffsetDateTime.parse(event.venues.first().doorsOpenDate).toLocalDate(),
                posterUrlFrom(gigUrl, event.images.square),
                fetchDescription(client, gigUrl, ::diceEventPageContent),
            )
        }
    }

    // dice.fm blocks requests without a browser-like User-Agent
    private val browserUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}

val blondiesBreweryTaproom = Venue(VenueId("blondies-taproom"), "Blondies Brewery Taproom")

class BlondiesBreweryTaproomGigsSource(client: HttpHandler) :
    GigsSource by DiceVenueGigsSource(client, url = "https://dice.fm/venue/blondies-brewery-m9nl?lng=en", venue = blondiesBreweryTaproom)

val blondiesBar = Venue(VenueId("blondies-bar"), "Blondies Bar")

class BlondiesBarGigsSource(client: HttpHandler) :
    GigsSource by DiceVenueGigsSource(client, url = "https://dice.fm/venue/blondies-rmvw?lng=en", venue = blondiesBar)

val helgis = Venue(VenueId("helgis"), "Helgi's")

class HelgisGigsSource(client: HttpHandler) :
    GigsSource by DiceVenueGigsSource(client, url = "https://dice.fm/venue/helgis-berx?lng=en", venue = helgis)

val barfly = Venue(VenueId("barfly"), "Barfly")

class BarflyGigsSource(client: HttpHandler) :
    GigsSource by DiceVenueGigsSource(client, url = "https://dice.fm/venue/barfly-camden-jqa4?lng=en", venue = barfly)

// A dice.fm event page renders almost nothing server-side to select from: the description lives in
// the __NEXT_DATA__ blob, under a props.pageProps.initialState that is itself a JSON-encoded string,
// so it has to be parsed a second time to reach event.event.about.description.
internal fun diceEventPageContent(page: Document): String? {
    val nextDataJson = page.select("script#__NEXT_DATA__").firstOrNull()?.data()
    val initialStateJson = nextDataJson
        ?.let { parseJsonNode(it).orThrow() }
        ?.field("props")?.field("pageProps")?.field("initialState")?.stringOrNull()
    return initialStateJson
        ?.let { parseJsonNode(it).orThrow() }
        ?.field("event")?.field("event")?.field("about")?.field("description")?.stringOrNull()
}

private fun JsonNode.field(key: String): JsonNode? = (this as? JsonNodeObject)?._fieldMap?.get(key)
private fun JsonNode.stringOrNull(): String? = (this as? JsonNodeString)?.text

private object JDiceNextData : JAny<DiceNextData>() {
    private val props by obj(JDiceProps, DiceNextData::props)
    override fun JsonNodeObject.deserializeOrThrow() = DiceNextData(+props)
}

private object JDiceProps : JAny<DiceProps>() {
    private val pageProps by obj(JDicePageProps, DiceProps::pageProps)
    override fun JsonNodeObject.deserializeOrThrow() = DiceProps(+pageProps)
}

private object JDicePageProps : JAny<DicePageProps>() {
    private val profile by obj(JDiceProfile, DicePageProps::profile)
    override fun JsonNodeObject.deserializeOrThrow() = DicePageProps(+profile)
}

private object JDiceProfile : JAny<DiceProfile>() {
    private val sections by array(JDiceSection, DiceProfile::sections)
    override fun JsonNodeObject.deserializeOrThrow() = DiceProfile(+sections)
}

private object JDiceSection : JAny<DiceSection>() {
    private val events by array(JDiceEvent, DiceSection::events)
    override fun JsonNodeObject.deserializeOrThrow() = DiceSection(+events)
}

private object JDiceEvent : JAny<DiceEvent>() {
    private val name by str(DiceEvent::name)
    private val perm_name by str(DiceEvent::permName)
    private val images by obj(JDiceImages, DiceEvent::images)
    private val venues by array(JDiceEventVenue, DiceEvent::venues)
    override fun JsonNodeObject.deserializeOrThrow() = DiceEvent(
        name = +name,
        permName = +perm_name,
        images = +images,
        venues = +venues,
    )
}

private object JDiceEventVenue : JAny<DiceEventVenue>() {
    private val doors_open_date by str(DiceEventVenue::doorsOpenDate)
    override fun JsonNodeObject.deserializeOrThrow() = DiceEventVenue(+doors_open_date)
}

private object JDiceImages : JAny<DiceImages>() {
    private val square by str(DiceImages::square)
    override fun JsonNodeObject.deserializeOrThrow() = DiceImages(+square)
}

private data class DiceNextData(val props: DiceProps)
private data class DiceProps(val pageProps: DicePageProps)
private data class DicePageProps(val profile: DiceProfile)
private data class DiceProfile(val sections: List<DiceSection>)
private data class DiceSection(val events: List<DiceEvent>)
private data class DiceEvent(val name: String, val permName: String, val images: DiceImages, val venues: List<DiceEventVenue>)
private data class DiceEventVenue(val doorsOpenDate: String)
private data class DiceImages(val square: String)
