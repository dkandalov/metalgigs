import dev.forkhandles.result4k.onFailure
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.ChatRequest
import org.http4k.ai.llm.chat.ChatResponseFormat
import org.http4k.ai.llm.model.Content
import org.http4k.ai.llm.model.ModelParams
import org.http4k.ai.model.ModelName
import org.http4k.ai.model.Temperature
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Instant

// starter list; extend as false negatives/positives turn up in practice
private val metalKeywords = listOf(
    "metal", "doom", "sludge", "grind", "grindcore", "death metal", "black metal",
    "thrash", "stoner", "hardcore", "deathcore", "metalcore", "crust", "powerviolence",
)

// some venues' event pages need special handling to get at the gig's own content:
// - The Underworld embeds a sitewide "other events" widget alongside the actual gig content,
//   so scanning the whole page picks up unrelated shows' titles
// - New Cross Inn (pit.live) renders its description client-side via Alpine.js: the text lives
//   in an x-html attribute, not as element text, so the plain page text never contains it
private val eventPageContentByVenue: Map<String, (Document) -> String?> = mapOf(
    "The Underworld" to { page -> page.select("article.event").let { if (it.isEmpty()) null else it.text() } },
    "New Cross Inn" to { page -> page.select("[x-ref=desc]").firstOrNull()?.attr("x-html") },
)

fun matchKeywords(pageText: String): List<String> =
    metalKeywords.filter { pageText.contains(it, ignoreCase = true) }

private fun eventPageContentText(pageHtml: String, url: String, venue: String): String {
    val page = Jsoup.parse(pageHtml, url)
    val extractContent = eventPageContentByVenue[venue] ?: return page.text()
    return extractContent(page) ?: error("Could not extract event page content for $venue at $url")
}

fun classifyGigByKeywords(client: HttpHandler, gig: GigEvent, recordedAt: Instant): GigClassified {
    val pageText = eventPageContentText(fetchPage(client, gig.url), gig.url, gig.venue)
    val matchedKeywords = matchKeywords(pageText)
    return GigClassified(
        venue = gig.venue,
        url = gig.url,
        recordedAt = recordedAt,
        genre = if (matchedKeywords.isNotEmpty()) Genre.Metal else Genre.Other,
        matchedKeywords = matchedKeywords,
        source = ClassificationSource.Keywords,
    )
}

val llmClassifierSystemPrompt = """
    You classify UK live music gig listings by genre. Given a gig's title and the text of its own
    event page, reply with exactly one word and nothing else:
    Metal - if the gig is metal, doom, sludge, grindcore, black/death metal, metalcore, deathcore,
    thrash, stoner, hardcore, crust, or a closely related heavy genre.
    Other - for anything else, including when you're not sure.
""".trimIndent()

private val llmClassifierModel = ModelName.of("claude-haiku-4-5-20251001")

fun classifyGigByLLM(client: HttpHandler, chat: Chat, gig: GigEvent, recordedAt: Instant): GigClassified {
    val pageText = eventPageContentText(fetchPage(client, gig.url), gig.url, gig.venue)

    val request = ChatRequest(
        "Title: ${gig.title}\n\nEvent page text: $pageText",
        ModelParams(llmClassifierModel, Temperature.ZERO, responseFormat = ChatResponseFormat.Text),
    )
    val response = chat(request).onFailure { error("LLM classification failed for ${gig.venue} at ${gig.url}: $it") }
    val reply = response.message.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
    val genre = Genre.entries.find { it.name.equals(reply, ignoreCase = true) }
        ?: error("Unexpected LLM classification reply for ${gig.venue} at ${gig.url}: \"$reply\"")

    return GigClassified(
        venue = gig.venue,
        url = gig.url,
        recordedAt = recordedAt,
        genre = genre,
        source = ClassificationSource.LLM,
    )
}

fun classifyGigs(
    gigs: List<GigEvent>,
    alreadyClassified: Set<Pair<String, String>>,
    limit: Int? = null,
    classifyGig: (GigEvent) -> GigClassified,
): List<GigClassified> {
    val toClassify = gigs.filter { (it.venue to it.url) !in alreadyClassified }.sortedBy { it.date() }
    return (if (limit != null) toClassify.take(limit) else toClassify).map(classifyGig)
}
