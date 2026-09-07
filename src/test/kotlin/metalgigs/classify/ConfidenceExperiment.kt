package metalgigs.classify

import metalgigs.Confidence
import metalgigs.Genre
import metalgigs.GigId
import metalgigs.Ollama
import metalgigs.classify.labelling.LabelledGig
import metalgigs.classify.labelling.LabelledGigs
import metalgigs.classify.labelling.Split
import metalgigs.classify.labelling.splitOf
import metalgigs.httpClient
import metalgigs.llmCallTimeout
import metalgigs.ollamaCallTimeout
import org.http4k.ai.llm.chat.AnthropicAI
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.model.ApiKey
import org.http4k.ai.model.ModelName
import org.http4k.ai.model.SystemPrompt
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

// An experiment rather than a check on this project's behaviour: it puts prompts through the gigs a
// person labelled and reports what they got right, which way they got it wrong, and how sure they
// said they were.
//
// Why it asks once: docs/adr/0013-a-classifier-is-scored-against-gigs-a-person-labelled.md
//
// Skipped unless SCORE_CONFIDENCE is set, the way COMPARE_CLASSIFIERS gates its neighbour.
// SCORE_CONFIDENCE_LOCAL=<ollama tag> adds a model hosted here as a free candidate. Setting an env
// var is not an input Gradle can see, so asking again about an unchanged experiment needs
// --rerun-tasks.
class ConfidenceExperiment {

    @Test
    fun `scores prompts against the gigs a person labelled`() {
        assumeTrue(System.getenv("SCORE_CONFIDENCE") != null, "set SCORE_CONFIDENCE=1 to run this experiment")

        val dataset = LabelledGigs(File("src/test/resources/metalgigs/classify/labelling"))
        val labelled = dataset.all()
        // a gig whose page said too little went to its poster instead, and a text prompt asked about
        // that description is answering a different question from the one the label was given for
        val onText = labelled.filter { it.gig.description.value.length >= THIN_TEXT_THRESHOLD }
        val population = onText.map { it.gig }

        val candidates = listOf(
            "ungraded, billed" to paid(scoped = false),
            "scoped, billed" to paid(),
        ) + listOfNotNull(
            System.getenv("SCORE_CONFIDENCE_LOCAL")?.let { "scoped, $it here" to local(it) },
        )

        val results = candidates.map { (name, classifier) -> name to answersOf(name, classifier, population) }

        val report = report(labelled, onText, results)
        File("build/confidence-score.md").apply { parentFile.mkdirs() }.writeText(report)
        println(report)
    }

    private fun paid(scoped: Boolean = true): GigClassifier {
        val apiKey = ApiKey.of(
            System.getenv("ANTHROPIC_API_KEY") ?: error("scoring the billed classifier needs ANTHROPIC_API_KEY")
        )
        val http = httpClient(llmCallTimeout)
        return LlmGigClassifier(
            http,
            Chat.AnthropicAI(
                apiKey = apiKey,
                http = http,
                systemPrompt = SystemPrompt.of(if (scoped) scopedClassifierSystemPrompt else llmClassifierSystemPrompt),
            ),
            readVerdict = if (scoped) ::gradedVerdict else ::ungradedVerdict,
        )
    }

    private fun local(model: String): GigClassifier {
        val http = httpClient(ollamaCallTimeout)
        return LlmGigClassifier(
            http,
            Chat.Ollama(http, SystemPrompt.of(scopedClassifierSystemPrompt)),
            textModel = ModelName.of(model),
            visionModel = ModelName.of(model),
            readVerdict = ::gradedVerdict,
        )
    }
}

private fun report(
    labelled: List<LabelledGig>,
    onText: List<LabelledGig>,
    results: List<Pair<String, Answers>>,
): String {
    val labelFor = onText.associateBy { it.gig.id }
    val offPage = onText.count { !it.canBeDerivedPurelyFromText }
    val lines = mutableListOf(
        "# What the labelled gigs say",
        "",
        "${labelled.size} labelled gig(s), ${onText.size} of them judged on page text and asked about here. " +
            "$offPage of those carry a label the page itself cannot support (canBeDerivedPurelyFromText = " +
            "false) and are scored apart: counting them as failures measures the listing's silence rather " +
            "than the classifier (ADR 13).",
        "",
        "**missing** is a gig labelled Metal that the classifier called Other - it never reaches the page, " +
            "and nothing downstream reports that it didn't. **wrongly shown** is one it called Metal that a " +
            "person calls Non-metal - it appears on the page, where it can at least be seen and overridden.",
        "",
    )

    results.forEach { (name, answers) ->
        val scored = scored(answers, labelFor)
        lines += "## $name"
        lines += ""
        lines += "| split | gigs | correct | missing | wrongly shown | Metal recall | Metal precision |"
        lines += "|---|---|---|---|---|---|---|"
        listOf<Pair<String, (LabelledGig) -> Boolean>>(
            "train" to { splitOf(it.gig) == Split.Train },
            "test" to { splitOf(it.gig) == Split.Test },
            "both" to { true },
        ).forEach { (label, inSplit) ->
            val rows = scored.filter { it.labelled.canBeDerivedPurelyFromText && inSplit(it.labelled) }
            val heldMetal = rows.count { it.labelled.genre == Genre.Metal }
            val givenMetal = rows.count { it.given == Genre.Metal }
            val found = rows.count { it.labelled.genre == Genre.Metal && it.correct }
            lines += "| $label | ${rows.size} | ${rows.count { it.correct }} (${percent(rows.count { it.correct }, rows.size)}) | " +
                "${rows.count { it.labelled.genre == Genre.Metal && it.given == Genre.Other }} | " +
                "${rows.count { it.given == Genre.Metal && it.labelled.genre == Genre.Other }} | " +
                "$found/$heldMetal | $found/$givenMetal |"
        }
        lines += ""

        val unreachable = scored.filterNot { it.labelled.canBeDerivedPurelyFromText }
        if (unreachable.isNotEmpty()) {
            lines += "Of ${unreachable.size} gig(s) whose page cannot carry the label, it got " +
                "${unreachable.count { it.correct }} right - not counted above."
            lines += ""
        }

        // scored on the same rows the tables above use. The off-page rows are the hard ones and land
        // disproportionately in Low, so leaving them in flatters the gap between the two buckets
        val graded = scored.filter { it.confidence != null && it.labelled.canBeDerivedPurelyFromText }
        if (graded.isNotEmpty()) {
            lines += "| confidence | answers | correct | accuracy |"
            lines += "|---|---|---|---|"
            Confidence.entries.forEach { confidence ->
                val held = graded.filter { it.confidence == confidence }
                lines += "| $confidence | ${held.size} | ${held.count { it.correct }} | ${percent(held.count { it.correct }, held.size)} |"
            }
            lines += ""

            val offPageGraded = unreachable.filter { it.confidence != null }
            if (offPageGraded.isNotEmpty()) {
                val low = offPageGraded.count { it.confidence == Confidence.Low }
                lines += "Of ${offPageGraded.size} answer(s) about a page that cannot carry its label, $low said " +
                    "Low (${percent(low, offPageGraded.size)}), against " +
                    "${percent(graded.count { it.confidence == Confidence.Low }, graded.size)} of the rest."
                lines += ""
            }
        }
    }

    results.forEach { (name, answers) ->
        if (answers.failed.isNotEmpty()) {
            lines += "## $name had no answer for ${answers.failed.size} gig(s)"
            lines += ""
            lines += answers.failed.map { (gig, reason) -> "- **${gig.title}** - $reason" }
            lines += ""
        }
    }
    return lines.joinToString("\n")
}

private data class Scored(val labelled: LabelledGig, val given: Genre, val confidence: Confidence?) {
    val correct get() = given == labelled.genre
}

private fun scored(answers: Answers, labelFor: Map<GigId, LabelledGig>): List<Scored> =
    answers.verdicts.mapNotNull { (id, verdict) ->
        labelFor[id]?.let { Scored(it, verdict.genre, verdict.confidence) }
    }

private fun percent(part: Int, whole: Int) = if (whole == 0) "n/a" else "${part * 100 / whole}%"
