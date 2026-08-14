import com.ubertob.kondor.json.jsonnode.JsonNode
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.jsonnode.JsonNodeString
import com.ubertob.kondor.json.jsonnode.parseJsonNode
import dev.forkhandles.result4k.onFailure
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.ChatRequest
import org.http4k.ai.llm.chat.ChatResponseFormat
import org.http4k.ai.llm.model.Content
import org.http4k.ai.llm.model.Message
import org.http4k.ai.llm.model.ModelParams
import org.http4k.ai.llm.model.Resource
import org.http4k.ai.model.ModelName
import org.http4k.ai.model.Temperature
import org.http4k.connect.model.Base64Blob
import org.http4k.connect.model.MimeType
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Instant

// some venues' event pages need special handling to get at the gig's own content:
// - The Underworld embeds a sitewide "other events" widget alongside the actual gig content,
//   so scanning the whole page picks up unrelated shows' titles
// - New Cross Inn (pit.live) renders its description client-side via Alpine.js: the text lives
//   in an x-html attribute, not as element text, so the plain page text never contains it
// - Alexandra Palace's #event_content div (the obvious container to reach for) turned out to
//   share space with two different kinds of boilerplate: the whole-page text picked up the
//   sitewide nav menu ("Summer Season", "Food And Drink", ...), and #event_content itself also
//   holds a sidebar of generic quick-link buttons ("Buy Tickets", "How to get here", "FAQs", "Safe
//   and secure", "Accessibility", "Accommodation") repeated identically on every event page. Rather
//   than exclude boilerplate piece by piece as more of it turns up, this names only the two
//   containers that actually hold gig-specific text: the description block and the "Key
//   information" accordion (often real content - an artist bio, say)
// - Cart & Horses (Useyourlocal pub-website platform) has no scoping at all today, so the whole
//   page's nav and footer (opening times, address, social links) end up in every description
// - Our Black Heart and The Dome are both built on Squarespace's "Events" template - same
//   article.eventitem container on both, same fix
// - dice.fm event pages (Blondies Brewery Taproom, Blondies Bar, Helgi's, 229) render almost
//   nothing server-side to select from - the actual description lives in a __NEXT_DATA__ JSON
//   blob, itself containing a JSON-encoded string (props.pageProps.initialState) that has to be
//   parsed a second time to reach event.event.about.description
// - The Garage and The Grace (DHP Family, same shared scraper) both wrap their gig content in a
//   ".single-article--contains-list" section - the more obvious ".single-article" also matches
//   its own *outer* wrapper div (which carries that same class alongside others), so selecting it
//   doubles every word of text
// - Roundhouse's ".event-about" section holds the real description alongside a "Related events"
//   carousel *nested inside it*, not a sibling - excluding that one sub-block by class is what
//   keeps other shows' titles out
// - Paper Dress Vintage's ".event__content" holds just the description, cleanly separate from the
//   sitewide nav and footer that whole-page text would otherwise pick up
private val squarespaceEventItem: (Document) -> String? = { page -> page.select("article.eventitem").let { if (it.isEmpty()) null else it.text() } }
private val dhpSingleArticle: (Document) -> String? = { page -> page.select(".single-article--contains-list").let { if (it.isEmpty()) null else it.text() } }

private fun JsonNode.field(key: String): JsonNode? = (this as? JsonNodeObject)?._fieldMap?.get(key)
private fun JsonNode.stringOrNull(): String? = (this as? JsonNodeString)?.text

private val diceEventDescription: (Document) -> String? = { page ->
    val nextDataJson = page.select("script#__NEXT_DATA__").firstOrNull()?.data()
    val initialStateJson = nextDataJson
        ?.let { parseJsonNode(it).orThrow() }
        ?.field("props")?.field("pageProps")?.field("initialState")?.stringOrNull()
    initialStateJson
        ?.let { parseJsonNode(it).orThrow() }
        ?.field("event")?.field("event")?.field("about")?.field("description")?.stringOrNull()
}

private val eventPageContentByVenue: Map<String, (Document) -> String?> = mapOf(
    "The Underworld" to { page -> page.select("article.event").let { if (it.isEmpty()) null else it.text() } },
    "New Cross Inn" to { page -> page.select("[x-ref=desc]").firstOrNull()?.attr("x-html") },
    "Alexandra Palace" to { page -> page.select(".ap_text_block, #key-information").let { if (it.isEmpty()) null else it.text() } },
    "Cart & Horses" to { page -> page.select(".page_header, .page_content_inner").let { if (it.isEmpty()) null else it.text() } },
    "Blondies Brewery Taproom" to diceEventDescription,
    "Blondies Bar" to diceEventDescription,
    "Helgi's" to diceEventDescription,
    "229" to diceEventDescription,
    "The Garage" to dhpSingleArticle,
    "The Grace" to dhpSingleArticle,
    "Our Black Heart" to squarespaceEventItem,
    "The Dome" to squarespaceEventItem,
    "Electric Ballroom" to { page -> page.select("article").let { if (it.isEmpty()) null else it.text() } },
    "Dingwalls" to { page -> page.select(".elementor-location-single").let { if (it.isEmpty()) null else it.text() } },
    "Roundhouse" to { page ->
        page.select(".event-hero__heading-wrapper, section.event-about .layout-block:not(.layout-block--related-events-list)")
            .let { if (it.isEmpty()) null else it.text() }
    },
    "Union Chapel" to { page -> page.select("article.pt-4, .sidebar").let { if (it.isEmpty()) null else it.text() } },
    "Scala" to { page -> page.select(".event-post").let { if (it.isEmpty()) null else it.text() } },
    "Paper Dress Vintage" to { page -> page.select(".event__content").let { if (it.isEmpty()) null else it.text() } },
)

private fun eventPageContentText(pageHtml: String, url: String, venue: Venue): String {
    val page = Jsoup.parse(pageHtml, url)
    val extractContent = eventPageContentByVenue[venue.name] ?: return page.text()
    return extractContent(page) ?: error("Could not extract event page content for $venue at $url")
}

fun fetchGigPageText(client: HttpHandler, url: String, venue: Venue): String =
    eventPageContentText(fetchPage(client, url), url, venue)

val llmClassifierSystemPrompt = """
    You classify UK live music gig listings by genre. Given a gig's title and the text of its own
    event page, reply with exactly one word and nothing else:
    Metal - if the gig is metal, doom, sludge, grindcore, black/death metal, metalcore, deathcore,
    thrash, stoner, hardcore, crust, or a closely related heavy genre.
    Other - for anything else, including when you're not sure.
    When the event page text is too sparse to judge and a poster image is included instead, use the
    image the same way - band logos, artwork style, and typography can indicate metal even without text.
    You are never being asked to identify anyone pictured, only to judge the genre, so don't say so -
    just give the one-word answer, on its own, with no explanation or caveats before it.
""".trimIndent()

private val llmClassifierModel = ModelName.of("claude-haiku-4-5-20251001")

// Below this, the text extracted from an event page is usually boilerplate/placeholder rather than
// anything descriptive - fall back to the poster image (with a stronger, vision-capable model)
// instead of guessing from it.
private const val THIN_TEXT_THRESHOLD = 80
private val visionClassifierModel = ModelName.of("claude-sonnet-5")

// the prompt asks for one bare word, and usually gets it - but the model sometimes prefixes the
// answer with a caveat (notably "I can't identify people in images" when judging a poster), so a
// preamble on earlier lines is tolerated. The answer line itself still has to be just the genre,
// give or take trailing punctuation, rather than the genre being fished out of a sentence
fun genreFromReply(reply: String): Genre? {
    val answer = reply.lines().lastOrNull { it.isNotBlank() }?.trim()?.trimEnd('.', '!') ?: return null
    return Genre.entries.find { it.name.equals(answer, ignoreCase = true) }
}

// posterImage is injectable so tests can exercise the vision path without a real image or
// ImageMagick; the default resizes to what the model actually needs (see fetchPosterForClassifying)
fun classifyGigByLLM(
    client: HttpHandler,
    chat: Chat,
    gig: Gig,
    recordedAt: Instant,
    posterImage: (HttpHandler, String) -> Content.Image = ::fetchPosterForClassifying,
): GigClassified {
    val description = gig.description.ifBlank { fetchGigPageText(client, gig.id.url, gig.id.venue) }
    val useVision = description.length < THIN_TEXT_THRESHOLD && gig.imageUrl.isNotBlank()

    val contents = listOf(Content.Text("Title: ${gig.title}\n\nEvent page text: $description")) +
        if (useVision) listOf(posterImage(client, gig.imageUrl)) else emptyList()

    // the vision model rejects a temperature override outright; the text model accepts one and we
    // want its verdicts reproducible, so only that path pins it
    val model = if (useVision) visionClassifierModel else llmClassifierModel
    val params = if (useVision) {
        ModelParams(model, responseFormat = ChatResponseFormat.Text)
    } else {
        ModelParams(model, Temperature.ZERO, responseFormat = ChatResponseFormat.Text)
    }

    val response = chat(ChatRequest(Message.User(contents), params))
        .onFailure { error("LLM classification failed for ${gig.id.venue} at ${gig.id.url}: $it") }
    val reply = response.message.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
    val genre = genreFromReply(reply)
        ?: error("Unexpected LLM classification reply for ${gig.id.venue} at ${gig.id.url}: \"$reply\"")

    return GigClassified(
        id = gig.id,
        recordedAt = recordedAt,
        genre = genre,
        source = ClassificationSource.LLM,
        llmModel = model.value,
        useVision = useVision,
    )
}

data class ClassificationRun(
    val classified: List<GigClassified>,
    val failed: List<Pair<Gig, String>>,
)

// one gig the model can't judge - a poster too big to send, an event page that won't load - must
// not discard the classifications made before it. Classifying is slow and every call is paid for,
// so a failure late in a long run used to throw away everything earlier in it. Failures are
// collected and reported instead, and those gigs simply stay Pending for a later run
fun classifyGigs(
    gigs: List<Gig>,
    alreadyClassified: Set<GigId>,
    limit: Int? = null,
    classifyGig: (Gig) -> GigClassified,
): ClassificationRun {
    val toClassify = gigs.filter { it.id !in alreadyClassified }.sortedBy { it.date }
    val results = (if (limit != null) toClassify.take(limit) else toClassify)
        .map { gig -> gig to runCatching { classifyGig(gig) } }

    return ClassificationRun(
        classified = results.mapNotNull { (_, result) -> result.getOrNull() },
        failed = results.mapNotNull { (gig, result) ->
            result.exceptionOrNull()?.let { gig to (it.message ?: it.toString()) }
        },
    )
}
