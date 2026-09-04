package metalgigs.classify.labelling

import metalgigs.Gig
import metalgigs.GigClassified
import metalgigs.GigId
import metalgigs.GigsLog
import metalgigs.Ollama
import metalgigs.classify.GigClassifier
import metalgigs.classify.LlmGigClassifier
import metalgigs.classify.THIN_TEXT_THRESHOLD
import metalgigs.classify.answersOf
import metalgigs.classify.llmClassifierSystemPrompt
import metalgigs.classify.recordedVerdicts
import metalgigs.httpClient
import metalgigs.ollamaCallTimeout
import metalgigs.venue
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.model.ModelName
import org.http4k.ai.model.SystemPrompt
import java.io.File

// Why there is no queue, and why this is a main(): docs/adr/0013-a-classifier-is-scored-against-gigs-a-person-labelled.md
fun main(args: Array<String>) {
    val dataset = LabelledGigs(File("src/test/resources/metalgigs/classify/labelling"))
    val log = GigsLog(File("events.ndjson"))

    when (args.firstOrNull()) {
        "gigs-awaiting-labels" -> printGigsAwaitingLabels(log, dataset, args.drop(1))
        "record-labels" -> recordLabels(log, dataset, File(args.getOrNull(1) ?: usage()))
        else -> usage()
    }
}

private fun usage(): Nothing = error(
    "usage:\n" +
        "  gigs-awaiting-labels [batch] [--models-to-find-disagreements <ollama-tag>[,<tag>...]]\n" +
        "  record-labels <file of labels to read>"
)

private fun printGigsAwaitingLabels(log: GigsLog, dataset: LabelledGigs, args: List<String>) {
    val batchSize = args.firstOrNull()?.takeIf { !it.startsWith("--") }?.toInt() ?: 5
    val models = args.valueOf("--models-to-find-disagreements")

    val waiting = gigsAwaitingLabels(log, dataset.settled())
    val batch = when (models) {
        null -> waiting.take(batchSize).map { it to "" }
        else -> disagreementsAmong(waiting, classifiers(models, log), batchSize)
    }

    // the copy is printed whole: a lineup or a named genre is often a long way down, and it is the
    // same text the classifiers were given
    println(
        batch.joinToString("\n\n") { (gig, note) ->
            "- [${gig.title}](${gig.id.url}) - ${venue(gig.id.venueId)}, ${gig.date}\n" +
                (if (note.isEmpty()) "" else "  $note\n") +
                "  > ${gig.description.value.replace("\n", " ")}"
        }.ifEmpty { "no gigs awaiting labels" }
    )
}

private fun recordLabels(log: GigsLog, dataset: LabelledGigs, labelsFile: File) {
    if (!labelsFile.exists()) return println("no such file: ${labelsFile.path}")

    val gigs = log.currentGigs().associateBy { it.id.url.value }
    val settled = dataset.settled()
    val labels = labelsFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val (verdict, url, why) = line.split(" ", limit = 3).let {
                check(it.size >= 2) {
                    "a label is '<metal|other|exclude>[-offpage] <url> [why]', which \"$line\" isn't"
                }
                Triple(it[0], it[1], it.getOrElse(2) { "" })
            }
            val gig = gigs[url] ?: error("the log holds no current gig at $url")
            verdict.removeSuffix(OFF_PAGE) to LabelledGig(
                gig,
                genreNamed(verdict.removeSuffix(OFF_PAGE)),
                why,
                canBeDerivedPurelyFromText = !verdict.endsWith(OFF_PAGE),
            )
        }
        .filterNot { (_, label) -> label.gig.id in settled }

    val (excluded, labelled) = labels.partition { (verdict, _) -> verdict == EXCLUDE }
    dataset.add(labelled.map { (_, label) -> label })
    dataset.exclude(excluded.map { (_, label) -> ExcludedGig(label.gig, label.why) })

    // the file is left where it was found - what stops a label being recorded twice is the set already
    // holding that gig
    println(
        "${labelled.size} label(s) and ${excluded.size} exclusion(s) added. The set now holds " +
            "${dataset.read(Split.Train).size} train and ${dataset.read(Split.Test).size} test, " +
            "with ${dataset.excluded().size} left out and " +
            "${dataset.all().count { !it.canBeDerivedPurelyFromText }} the page cannot answer."
    )
}

// Ordered by url rather than date, so a batch spreads across the listing rather than taking the next
// fortnight at whichever venues book earliest, and stopping and starting picks up where it left off.
internal fun gigsAwaitingLabels(log: GigsLog, settled: Set<GigId>): List<Gig> {
    val judged = log.entries.filterIsInstance<GigClassified>().map { it.id }.toSet()
    return log.currentGigs()
        .filter { it.id in judged && it.id !in settled && it.description.value.length >= THIN_TEXT_THRESHOLD }
        .sortedBy { it.id.url.value.hashCode() }
}

// Asked one gig at a time and stopped as soon as the batch is full, so the cost is set by how many
// labels are wanted: docs/adr/0013-a-classifier-is-scored-against-gigs-a-person-labelled.md
internal fun disagreementsAmong(
    gigs: List<Gig>,
    classifiers: List<Pair<String, GigClassifier>>,
    wanted: Int,
): List<Pair<Gig, String>> =
    gigs.asSequence()
        .take(MOST_TO_EXAMINE)
        .mapNotNull { gig -> disagreementOver(gig, classifiers) }
        .take(wanted)
        .toList()

private fun classifiers(models: String, log: GigsLog) =
    listOf("the log" to recordedVerdicts(log)) +
        models.split(",").map { it.trim() }.map { "$it here" to localClassifier(it) }

private fun disagreementOver(gig: Gig, classifiers: List<Pair<String, GigClassifier>>): Pair<Gig, String>? {
    val given = classifiers.mapNotNull { (name, classifier) ->
        answersOf(name, classifier, listOf(gig)).verdicts[gig.id]?.let { name to it.genre }
    }
    // a gig some classifier had no answer for is no disagreement - there is nothing to disagree about
    if (given.size < classifiers.size || given.map { it.second }.toSet().size < 2) return null
    return gig to "disagreed - ${given.joinToString(", ") { "${it.first}: ${it.second}" }}"
}

// so that a log with no disagreements left gives up rather than reading all of it
private const val MOST_TO_EXAMINE = 200

// an excluded gig is carried as the same row shape as a labelled one and needs some genre to be
// that, so it takes Other - nothing reads it, an exclusion being written to a file of its own
private fun genreNamed(name: String) =
    if (name == EXCLUDE) metalgigs.Genre.Other
    else metalgigs.Genre.entries.find { it.name.equals(name, ignoreCase = true) }
        ?: error("a decision says metal, other or exclude, not \"$name\"")

// the classifier the daily run uses, pointed at a chat this machine answers, so what differs between
// it and the log is the model rather than a second implementation of the classifier
private fun localClassifier(model: String): GigClassifier {
    val http = httpClient(ollamaCallTimeout)
    return LlmGigClassifier(
        http,
        Chat.Ollama(http, SystemPrompt.of(llmClassifierSystemPrompt)),
        textModel = ModelName.of(model),
        visionModel = ModelName.of(model),
    )
}

private fun List<String>.valueOf(flag: String) = indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

private const val EXCLUDE = "exclude"
private const val OFF_PAGE = "-offpage"
