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
import kotlin.math.ceil

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
)

private fun eventPageContentText(pageHtml: String, url: String, venue: Venue): String {
    val page = Jsoup.parse(pageHtml, url)
    val extractContent = eventPageContentByVenue[venue.name] ?: return page.text()
    return extractContent(page) ?: error("Could not extract event page content for $venue at $url")
}

fun fetchGigPageText(client: HttpHandler, gig: Gig): String =
    eventPageContentText(fetchPage(client, gig.id.url), gig.id.url, gig.id.venue)

// cross-checks a venue's gigs against each other: real gig-specific text (lineup, ticket info,
// dates) wouldn't coincidentally repeat between different gigs, but sitewide boilerplate the
// extraction above failed to strip out (nav, footer, cookie notice, venue address) appears
// identically on every one of that venue's pages. Flags venues where enough gigs are made up mostly
// of such shared text, as candidates for the eventPageContentByVenue treatment above - this only
// surfaces the problem rather than trying to auto-strip the shared text, which risks eating real
// content along with the boilerplate.
//
// Checked against the *fraction* of each gig's own words that are shared, not just whether any of
// it repeats at all: real venues often print the same short policy line (an age restriction, an ID
// requirement) on every event page as genuine, correctly-scoped content, and that alone shouldn't
// read as contamination the way a whole nav menu and footer glued onto (or standing in for) the
// actual gig text would. Measured against real scraped data, venues with a scraping bug clustered
// far above this 50% line (0.91-1.00) while a venue with only a recurring disclaimer sat well below
// it (0.76 for one already-fixed venue whose extraction has nothing left to narrow further)
private const val SHARED_PHRASE_WORDS = 6
private const val CONTAMINATED_WORD_FRACTION = 0.5

private fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

private fun wordNGrams(words: List<String>): List<String> = words.windowed(SHARED_PHRASE_WORDS, 1).map { it.joinToString(" ") }

fun likelyContaminatedVenues(gigs: List<Gig>): Map<Venue, Int> =
    gigs.filter { it.description.isNotBlank() }
        .groupBy { it.id.venue }
        .filter { (_, venueGigs) -> venueGigs.size >= 3 }
        .mapNotNull { (venue, venueGigs) ->
            val wordsByGig = venueGigs.associateWith { words(it.description) }
            val ngramsByGig = wordsByGig.mapValues { (_, ws) -> wordNGrams(ws) }
            // a phrase has to recur across at least half that venue's gigs (never fewer than two) to
            // count as shared - one coincidental overlap between two unrelated blurbs isn't boilerplate.
            // Each gig's own windows are deduped first, so a phrase repeated many times within just one
            // gig's own text (e.g. the same short filler line printed several times on one page) isn't
            // mistaken for something shared *across* gigs
            val minSharedBy = ceil(venueGigs.size / 2.0).toInt().coerceAtLeast(2)
            val sharedNGrams = ngramsByGig.values.map { it.toSet() }.flatten()
                .groupingBy { it }.eachCount()
                .filterValues { it >= minSharedBy }
                .keys

            val affected = wordsByGig.count { (gig, ws) ->
                if (ws.isEmpty()) return@count false
                val sharedWindows = ngramsByGig.getValue(gig).count { it in sharedNGrams }
                // each window only proves its own 6 words are shared, but overlapping windows over a
                // long shared run would otherwise be counted many times over, so this caps the estimate
                // at the gig's actual word count rather than trying to track the exact covered span
                val sharedWordEstimate = (sharedWindows * SHARED_PHRASE_WORDS).coerceAtMost(ws.size)
                sharedWordEstimate.toDouble() / ws.size >= CONTAMINATED_WORD_FRACTION
            }
            if (affected > 0) venue to affected else null
        }
        .toMap()

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
    val description = gig.description.ifBlank { fetchGigPageText(client, gig) }
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
