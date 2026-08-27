package metalgigs.classify

import metalgigs.ClassificationSource
import metalgigs.Genre
import metalgigs.Gig
import metalgigs.GigClassified
import metalgigs.GigId
import metalgigs.GigsLog
import metalgigs.venue
import java.util.Locale

// Asks several classifiers the same gigs and prints where they differ. What a classifier is - a
// model behind a prompt, a venue rule, the verdicts a log already holds - is the caller's business:
// the harness only knows that each one answers a Gig with a GigClassified, so a prompt, a model and
// a hand-written rule are all comparable on the same terms and by the same numbers.
//
// One of them is the reference the rest are scored against. That is a choice about what "right"
// means for a run rather than a property of the harness: score against the log and the numbers say
// how well a candidate reproduces what is published today, score against a person's overrides and
// they say how often it is right.
internal fun compareClassifiers(
    population: List<Gig>,
    reference: Pair<String, GigClassifier>,
    vararg candidates: Pair<String, GigClassifier>,
): String {
    val answers = (listOf(reference) + candidates).map { (name, classifier) -> answersOf(name, classifier, population) }
    val (referenceAnswers, candidateAnswers) = answers.first() to answers.drop(1)

    return (
        heading(population, referenceAnswers, candidateAnswers) +
            candidateAnswers.flatMap { scoredAgainst(referenceAnswers, it) } +
            whereCandidatesDiffer(referenceAnswers, candidateAnswers) +
            answers.flatMap(::failures)
        ).joinToString("\n")
}

// What one classifier made of the population, and what it cost to ask. classifyGigs is what the
// daily run puts a classifier through, so a comparison sees the same failure handling the pipeline
// does: a gig its classifier throws on is one missing answer rather than a run that stops.
internal data class Answers(
    val name: String,
    val verdicts: Map<GigId, GigClassified>,
    val failed: List<Pair<Gig, String>>,
    val seconds: Long,
) {
    val inputTokens get() = verdicts.values.sumOf { it.inputTokens ?: 0 }
    val cost get() = verdicts.values.sumOf { classificationCost(it) ?: 0.0 }
}

internal fun answersOf(name: String, classifier: GigClassifier, population: List<Gig>): Answers {
    val startedAt = System.nanoTime()
    val run = classifyGigs(population, emptySet(), classifier = classifier)
    return Answers(
        name,
        run.classified.associateBy { it.id },
        run.failed,
        (System.nanoTime() - startedAt) / 1_000_000_000,
    )
}

private fun heading(population: List<Gig>, reference: Answers, candidates: List<Answers>): List<String> =
    listOf(
        "# ${candidates.joinToString(", ") { it.name }} vs ${reference.name}",
        "",
        "${population.size} gig(s), ${population.minOfOrNull { it.date }} to ${population.maxOfOrNull { it.date }}, " +
            "across ${population.map { it.id.venueId }.toSet().size} venue(s).",
        "",
        "| classifier | answered | failed | seconds | input tokens | cost |",
        "|---|---|---|---|---|---|",
    ) + (listOf(reference) + candidates).map {
        "| ${it.name} | ${it.verdicts.size} | ${it.failed.size} | ${it.seconds} | ${it.inputTokens} | " +
            "${if (it.cost == 0.0) "-" else money(it.cost)} |"
    } + ""

// Only the gigs both answered can be scored: one that a classifier failed on is a gap in the
// evidence rather than a wrong answer, and counting it either way would make a broken run look like
// a considered one.
private fun scoredAgainst(reference: Answers, candidate: Answers): List<String> {
    val shared = reference.verdicts.keys intersect candidate.verdicts.keys
    val judged = shared.map { id -> reference.verdicts.getValue(id).genre to candidate.verdicts.getValue(id).genre }
    if (judged.isEmpty()) return listOf("## ${candidate.name} vs ${reference.name}", "", "_nothing both answered_", "")

    fun count(on: Genre, given: Genre) = judged.count { it == on to given }
    fun held(genre: Genre) = Genre.entries.sumOf { count(genre, it) }
    val agreed = Genre.entries.sumOf { count(it, it) }

    return listOf(
        "## ${candidate.name} vs ${reference.name}",
        "",
        "${judged.size} gig(s) both answered, $agreed agreed (${percent(agreed, judged.size)}).",
        "",
        "| ${reference.name} | ${candidate.name} | count |",
        "|---|---|---|",
    ) + Genre.entries.flatMap { on -> Genre.entries.map { given -> "| $on | $given | ${count(on, given)} |" } } + listOf(
        "",
        // Metal is the genre the page is made of, so the two ways of being wrong about it are worth
        // naming apart: one empties the page and the other fills it with things that don't belong.
        "Of ${held(Genre.Metal)} gig(s) ${reference.name} calls Metal, ${candidate.name} finds " +
            "${count(Genre.Metal, Genre.Metal)} (${percent(count(Genre.Metal, Genre.Metal), held(Genre.Metal))}), " +
            "and adds ${count(Genre.Other, Genre.Metal)} more it doesn't.",
        "",
    )
}

// Where they don't all say the same thing - the gigs a choice between these classifiers is actually
// a choice about, listed so they can be read rather than counted.
private fun whereCandidatesDiffer(reference: Answers, candidates: List<Answers>): List<String> {
    val all = listOf(reference) + candidates
    // a gig only some of them answered is a gap in the evidence, not a disagreement, so the split is
    // read off what all of them got as far as answering
    val answeredByAll = all.map { it.verdicts.keys }.reduce { held, next -> held intersect next }
    val split = answeredByAll.filter { id -> all.map { it.verdicts.getValue(id).genre }.toSet().size > 1 }
    if (split.isEmpty()) return listOf("## They agreed on all ${answeredByAll.size} gig(s) they all answered", "")

    return listOf("## Where they disagree (${split.size} of ${answeredByAll.size})", "") +
        split.sortedBy { it.url.value }.map { id ->
            "- ${venue(id.venueId)} <${id.url}>\n" +
                all.joinToString("\n") { "  ${it.name}: ${it.verdicts.getValue(id).genre}" }
        } + ""
}

private fun failures(answers: Answers): List<String> =
    if (answers.failed.isEmpty()) emptyList()
    else listOf("## ${answers.name} had no answer for ${answers.failed.size} gig(s)", "") +
        answers.failed.map { (gig, reason) -> "- **${gig.title}** - $reason" } + ""

private fun percent(part: Int, whole: Int) = if (whole == 0) "n/a" else "${part * 100 / whole}%"

private fun money(amount: Double) = String.format(Locale.ROOT, "$%.4f", amount)

// The log's own verdicts, as a classifier, so that what a run is scored against is the same kind of
// thing as what is being scored and needs no special case anywhere above. A gig the log holds no
// entry of its own for has no answer here rather than a made up one - which classifyGigs records as
// a failure, and the scoring then leaves out.
internal fun recordedVerdicts(log: GigsLog): GigClassifier {
    val byGig = log.entries.filterIsInstance<GigClassified>()
        .groupBy { it.id }
        .mapValues { (_, entries) ->
            val latest = entries.groupBy { it.source }.mapValues { (_, of) -> of.maxBy { it.seq } }
            latest[ClassificationSource.User] ?: latest.getValue(ClassificationSource.LLM)
        }
    return GigClassifier { gig ->
        byGig[gig.id] ?: error("the log holds no classification of its own for ${gig.id.url}")
    }
}

// Spread across the whole list rather than the first n of it, so that a population cut down to
// something a slow classifier can be asked about is still the same spread of venues and months the
// whole of it was, rather than one venue's listing and the next few weeks.
internal fun <T> List<T>.spreadSample(wanted: Int): List<T> =
    if (wanted >= size) this else indices.filter { it * wanted / size != (it + 1) * wanted / size }.map { this[it] }
