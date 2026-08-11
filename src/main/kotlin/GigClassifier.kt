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
private val eventPageContentByVenue: Map<String, (Document) -> String?> = mapOf(
    "The Underworld" to { page -> page.select("article.event").let { if (it.isEmpty()) null else it.text() } },
    "New Cross Inn" to { page -> page.select("[x-ref=desc]").firstOrNull()?.attr("x-html") },
)

private fun eventPageContentText(pageHtml: String, url: String, venue: String): String {
    val page = Jsoup.parse(pageHtml, url)
    val extractContent = eventPageContentByVenue[venue] ?: return page.text()
    return extractContent(page) ?: error("Could not extract event page content for $venue at $url")
}

val llmClassifierSystemPrompt = """
    You classify UK live music gig listings by genre. Given a gig's title and the text of its own
    event page, reply with exactly one word and nothing else:
    Metal - if the gig is metal, doom, sludge, grindcore, black/death metal, metalcore, deathcore,
    thrash, stoner, hardcore, crust, or a closely related heavy genre.
    Other - for anything else, including when you're not sure.
    When the event page text is too sparse to judge and a poster image is included instead, use the
    image the same way - band logos, artwork style, and typography can indicate metal even without text.
""".trimIndent()

private val llmClassifierModel = ModelName.of("claude-haiku-4-5-20251001")

// below this, event page text is usually boilerplate/placeholder rather than a real description -
// fall back to the poster image (with a stronger, vision-capable model) instead of guessing from it
private const val THIN_TEXT_THRESHOLD = 80
private val visionClassifierModel = ModelName.of("claude-sonnet-5")

// the same gig is judged more than once and the samples compared: agreement means the model is
// consistent about this gig and its answer stands, disagreement means it isn't and a human should
// decide. Sampling only tells us anything at a non-zero temperature - at Temperature.ZERO the
// replies would be near-identical by construction and always "agree", which measures nothing
private const val SAMPLES_PER_GIG = 2

fun classifyGigByLLM(client: HttpHandler, chat: Chat, gig: GigEvent, recordedAt: Instant): GigClassified {
    val pageText = eventPageContentText(fetchPage(client, gig.url), gig.url, gig.venue)
    val useVision = pageText.length < THIN_TEXT_THRESHOLD && gig.imageUrl.isNotBlank()

    val contents = listOf(Content.Text("Title: ${gig.title}\n\nEvent page text: $pageText")) +
        if (useVision) listOf(fetchImageContent(client, gig.imageUrl)) else emptyList()

    // the vision model rejects a temperature override outright, so it's left at the API's own
    // default - which is non-zero, and so still varies between samples as this relies on
    val params = if (useVision) {
        ModelParams(visionClassifierModel, responseFormat = ChatResponseFormat.Text)
    } else {
        ModelParams(llmClassifierModel, Temperature.ONE, responseFormat = ChatResponseFormat.Text)
    }
    val request = ChatRequest(Message.User(contents), params)

    val sampledGenres = (1..SAMPLES_PER_GIG).map {
        val response = chat(request).onFailure { error("LLM classification failed for ${gig.venue} at ${gig.url}: $it") }
        val reply = response.message.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
        Genre.entries.find { it.name.equals(reply, ignoreCase = true) }
            ?: error("Unexpected LLM classification reply for ${gig.venue} at ${gig.url}: \"$reply\"")
    }

    return GigClassified(
        venue = gig.venue,
        url = gig.url,
        recordedAt = recordedAt,
        // when the samples disagree this verdict isn't used - the gig needs review either way -
        // but it's still recorded so the log holds a complete row rather than a hole
        genre = sampledGenres.first(),
        source = ClassificationSource.LLM,
        sampledGenres = sampledGenres,
    )
}

fun classifyGigs(
    gigs: List<GigEvent>,
    alreadyClassified: Set<GigId>,
    limit: Int? = null,
    classifyGig: (GigEvent) -> GigClassified,
): List<GigClassified> {
    val toClassify = gigs.filter { it.id !in alreadyClassified }.sortedBy { it.date() }
    return (if (limit != null) toClassify.take(limit) else toClassify).map(classifyGig)
}
