package metalgigs.classify

import metalgigs.GigClassified
import metalgigs.GigsLog
import metalgigs.Ollama
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

// An experiment rather than a check on this project's behaviour: it puts a model hosted on this
// machine, and optionally the paid classifier itself, through the same gigs the log already holds
// verdicts for, and prints where they part company. It needs a running ollama and takes a second a
// gig, so the suite skips it unless COMPARE_CLASSIFIERS names a model - the same way DEV_FLYER and
// RECENTLY_ADDED gate the other two. Setting it isn't an input Gradle can see, so a second run of
// an unchanged experiment is up-to-date and prints nothing: --rerun-tasks is what asks again.
//
// COMPARE_CLASSIFIERS_PAID=1 adds the real classifier as a second candidate, which is a real API
// call per gig and real money - the report prices the run, and the limit below is what bounds it.
class ClassifierComparisonExperiment {

    @Test
    fun `compares a local model, and optionally the paid one, against the log's own verdicts`() {
        val localModel = System.getenv("COMPARE_CLASSIFIERS")
        assumeTrue(localModel != null, "set COMPARE_CLASSIFIERS=<ollama model tag>[,<tag>...] to run this experiment")

        val log = GigsLog(File(System.getenv("COMPARE_CLASSIFIERS_LOG") ?: "events.ndjson"))
        // gigs the log answers for itself, judged on their own page text: one whose page said too
        // little went to its poster instead, and a text model asked about that description is being
        // asked a different question from the one the recorded verdict answers
        val judged = log.entries.filterIsInstance<GigClassified>().map { it.id }.toSet()
        val population = log.currentGigs()
            .filter { it.id in judged && it.description.value.length >= THIN_TEXT_THRESHOLD }
            .sortedWith(compareBy({ it.date.value }, { it.id.url.value }))
            .spreadSample(System.getenv("COMPARE_CLASSIFIERS_LIMIT")?.toInt() ?: 25)

        // more than one tag compares the models with each other as well as with the log, which is
        // what the disagreement list at the end of the report is for
        val candidates = localModel!!.split(",").map { it.trim() }.map { "$it here" to localClassifier(it) } +
            listOfNotNull(paidClassifier()?.let { "claude, billed" to it })

        val report = compareClassifiers(population, "the log" to recordedVerdicts(log), *candidates.toTypedArray())
        File(/* pathname = */ "build/classifier-comparison.md").apply { parentFile.mkdirs() }.writeText(report)
        println(report)
    }

    // The classifier the daily run uses, pointed at a chat this machine answers: same prompt, same
    // reply parsing, same fall back to the poster where a page says too little, so what the report
    // measures is the model rather than a second implementation of the classifier that happens to
    // resemble it.
    private fun localClassifier(model: String): GigClassifier {
        val http = httpClient(ollamaCallTimeout)
        return LlmGigClassifier(
            http,
            Chat.Ollama(http, SystemPrompt.of(llmClassifierSystemPrompt)),
            textModel = ModelName.of(model),
            visionModel = ModelName.of(model),
        )
    }

    private fun paidClassifier(): GigClassifier? {
        if (System.getenv("COMPARE_CLASSIFIERS_PAID") == null) return null
        val apiKey = ApiKey.of(
            System.getenv("ANTHROPIC_API_KEY")
                ?: error("COMPARE_CLASSIFIERS_PAID asks the billed classifier, which needs ANTHROPIC_API_KEY")
        )
        val http = httpClient(llmCallTimeout)
        return LlmGigClassifier(
            http,
            Chat.AnthropicAI(apiKey = apiKey, http = http, systemPrompt = SystemPrompt.of(llmClassifierSystemPrompt)),
        )
    }
}
