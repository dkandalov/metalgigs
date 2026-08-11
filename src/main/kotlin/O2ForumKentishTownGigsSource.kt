import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.JList
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.str
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import java.time.OffsetDateTime

// the venue's listing page is a Next.js SPA - its static HTML has no event data at all (the
// ld+json block below is served empty), so this source needs a client that actually runs the
// page's JS (see ChromeHeadless). Once rendered, the page embeds every event as a Schema.org
// MusicEvent, which is far better structured than the rendered DOM's generated CSS classes, so
// we parse that rather than scraping selectors.
private data class SchemaOffer(val url: String)
private data class SchemaMusicEvent(val name: String, val startDate: String, val image: String, val offers: List<SchemaOffer>)

private object JSchemaOffer : JAny<SchemaOffer>() {
    private val url by str(SchemaOffer::url)
    override fun JsonNodeObject.deserializeOrThrow() = SchemaOffer(url = +url)
}

private object JSchemaMusicEvent : JAny<SchemaMusicEvent>() {
    private val name by str(SchemaMusicEvent::name)
    private val startDate by str(SchemaMusicEvent::startDate)
    private val image by str(SchemaMusicEvent::image)
    private val offers by array(JSchemaOffer, SchemaMusicEvent::offers)

    override fun JsonNodeObject.deserializeOrThrow() = SchemaMusicEvent(
        name = +name,
        startDate = +startDate,
        image = +image,
        offers = +offers,
    )
}

class O2ForumKentishTownGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://www.academymusicgroup.com/o2forumkentishtown/events"
    override val venue = "O2 Forum Kentish Town"

    override fun latestGigs(): List<GigEvent> {
        val page = Jsoup.parse(fetchPage(client, url), url)
        val ldJson = page.select("script[type=application/ld+json]").firstOrNull()
            ?: error("Could not find ld+json on $url - the page may not have rendered (needs a JS-capable client)")
        val events = JList(JSchemaMusicEvent).fromJson(ldJson.data()).orThrow()
        check(events.isNotEmpty()) { "No events found in ld+json on $url - the page may not have finished rendering" }

        return events.map { event ->
            GigEvent.of(
                title = event.name,
                venue = venue,
                // e.g. "2026-08-11T22:59:00Z" - a doors/curfew timestamp, so only the date is used
                date = OffsetDateTime.parse(event.startDate).toLocalDate(),
                // no per-gig page on the venue's own site; its ticketing link is the stable per-gig url
                url = event.offers.first().url,
                imageUrl = event.image,
            )
        }
    }
}
