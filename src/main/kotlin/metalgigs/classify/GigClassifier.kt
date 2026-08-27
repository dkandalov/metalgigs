package metalgigs.classify

import dev.forkhandles.result4k.failureOrNull
import dev.forkhandles.result4k.onFailure
import dev.forkhandles.result4k.resultFrom
import dev.forkhandles.result4k.valueOrNull
import metalgigs.*
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.ChatRequest
import org.http4k.ai.llm.chat.ChatResponseFormat
import org.http4k.ai.llm.model.Content
import org.http4k.ai.llm.model.Message
import org.http4k.ai.llm.model.ModelParams
import org.http4k.ai.model.ModelName
import org.http4k.ai.model.Temperature
import org.http4k.core.HttpHandler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

fun interface GigClassifier {
    fun classify(gig: Gig): GigClassified
}

// Why a genre costs one paid call: docs/adr/0012-a-genre-is-one-paid-call-judged-on-text-or-poster.md
internal fun classifyGigs(
    gigs: List<Gig>,
    alreadyClassified: Set<GigId>,
    limit: Int? = null,
    classifier: GigClassifier,
): ClassificationRun {
    val toClassify = gigs.filter { it.id !in alreadyClassified }.sortedBy { it.date }
    val results = (if (limit != null) toClassify.take(limit) else toClassify)
        .map { gig -> gig to resultFrom { classifier.classify(gig) } }

    return ClassificationRun(
        results.mapNotNull { (_, result) -> result.valueOrNull() },
        results.mapNotNull { (gig, result) ->
            result.failureOrNull()?.let { gig to (it.message ?: it.toString()) }
        },
    )
}

internal data class ClassificationRun(
    val classified: List<GigClassified>,
    val failed: List<Pair<Gig, String>>,
)

internal class WithAlwaysMetalVenues(
    private val classifier: GigClassifier,
    private val recordedAt: Instant,
) : GigClassifier {
    override fun classify(gig: Gig): GigClassified =
        if (gig.id.venueId in alwaysMetalVenues) GigClassified(gig.id, recordedAt, Genre.Metal, ClassificationSource.User)
        else classifier.classify(gig)
}

internal class LlmGigClassifier(
    private val client: HttpHandler,
    private val chat: Chat,
    private val recordedAt: Instant,
    private val posterImage: (HttpHandler, PosterUrl) -> Content.Image = ::fetchPosterForClassifying,
    // named rather than fixed so that the same classifier can be pointed at a chat that isn't
    // Anthropic's - a model hosted here answers to its own tag and 404s under a claude one
    private val textModel: ModelName = llmClassifierModel,
    private val visionModel: ModelName = visionClassifierModel,
) : GigClassifier {

    override fun classify(gig: Gig): GigClassified {
        val useVision = gig.description.value.length < THIN_TEXT_THRESHOLD

        val contents = listOf(Content.Text(classifierPromptText(gig))) +
            if (useVision) listOf(posterImage(client, gig.posterUrl)) else emptyList()

        // the vision model rejects a temperature override outright
        val model = if (useVision) visionModel else textModel
        val params = if (useVision) {
            ModelParams(model, responseFormat = ChatResponseFormat.Text)
        } else {
            ModelParams(model, Temperature.ZERO, responseFormat = ChatResponseFormat.Text)
        }

        val response = chat(ChatRequest(Message.User(contents), params))
            .onFailure { error("LLM classification failed for ${venue(gig.id.venueId)} at ${gig.id.url}: $it") }
        val reply = response.message.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
        val genre = genreFromReply(reply)
            ?: error("Unexpected LLM classification reply for ${venue(gig.id.venueId)} at ${gig.id.url}: \"$reply\"")

        return GigClassified(
            id = gig.id,
            recordedAt = recordedAt,
            genre = genre,
            source = ClassificationSource.LLM,
            llmModel = model.value,
            useVision = useVision,
            inputTokens = response.metadata.usage?.input,
            outputTokens = response.metadata.usage?.output,
        )
    }
}

// What the model is shown about a gig beyond the system prompt.
internal fun classifierPromptText(gig: Gig) = "Title: ${gig.title}\n\nEvent page text: ${gig.description}"

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

// Why a preamble is tolerated: docs/adr/0012-a-genre-is-one-paid-call-judged-on-text-or-poster.md
internal fun genreFromReply(reply: String): Genre? {
    val answer = reply.lines().lastOrNull { it.isNotBlank() }?.trim()?.trimEnd('.', '!') ?: return null
    return Genre.entries.find { it.name.equals(answer, ignoreCase = true) }
}

private val llmClassifierModel = ModelName.of("claude-haiku-4-5-20251001")

// Why text below this falls back to the poster: docs/adr/0012-a-genre-is-one-paid-call-judged-on-text-or-poster.md
internal const val THIN_TEXT_THRESHOLD = 80
private val visionClassifierModel = ModelName.of("claude-sonnet-5")

// Why a run's cost is reported this way: docs/adr/0012-a-genre-is-one-paid-call-judged-on-text-or-poster.md
fun classificationCostReport(classifications: List<GigClassified>): List<String> {
    if (classifications.isEmpty()) return emptyList()

    val (vision, text) = classifications.partition { it.useVision == true }
    val lines = listOf("text" to text, "vision" to vision)
        .filter { (_, of) -> of.isNotEmpty() }
        .map { (label, of) ->
            "${label.padEnd(6)} ${of.size.toString().padStart(4)} gig(s)  " +
                "${of.sumOf { it.inputTokens ?: 0 }} in / ${of.sumOf { it.outputTokens ?: 0 }} out  ${money(of)}"
        }

    val unpriced = classifications.count { classificationCost(it) == null }
    return lines +
        listOfNotNull("$unpriced gig(s) reported no token usage, so are not counted above".takeIf { unpriced > 0 }) +
        "total  ${money(classifications)}"
}

private fun money(classifications: List<GigClassified>) =
    String.format(Locale.ROOT, "$%.4f", classifications.sumOf { classificationCost(it) ?: 0.0 })

internal fun classificationCost(classified: GigClassified): Double? {
    val rate = llmRate(classified.llmModel ?: return null, classified.recordedAt.atZone(ZoneOffset.UTC).toLocalDate())
    val input = classified.inputTokens ?: return null
    val output = classified.outputTokens ?: return null
    return rate?.let { input / 1_000_000.0 * it.inputPerMillion + output / 1_000_000.0 * it.outputPerMillion }
}

// From platform.claude.com/docs/en/pricing, read on 2026-08-15.
private fun llmRate(model: String, on: LocalDate): LlmRate? = when (model) {
    llmClassifierModel.value -> LlmRate(inputPerMillion = 1.00, outputPerMillion = 5.00)
    visionClassifierModel.value ->
        if (on < LocalDate.of(2026, 9, 1)) LlmRate(inputPerMillion = 2.00, outputPerMillion = 10.00)
        else LlmRate(inputPerMillion = 3.00, outputPerMillion = 15.00)
    else -> null
}

private data class LlmRate(val inputPerMillion: Double, val outputPerMillion: Double)
