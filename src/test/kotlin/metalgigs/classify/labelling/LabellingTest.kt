package metalgigs.classify.labelling

import metalgigs.ClassificationSource
import metalgigs.Genre
import metalgigs.Gig
import metalgigs.GigClassified
import metalgigs.GigDate
import metalgigs.GigDescription
import metalgigs.GigId
import metalgigs.GigObserved
import metalgigs.GigTitle
import metalgigs.GigUrl
import metalgigs.GigsLog
import metalgigs.PosterUrl
import metalgigs.classify.Classification
import metalgigs.classify.GigClassifier
import metalgigs.classify.THIN_TEXT_THRESHOLD
import metalgigs.scrape.venues.theUnderworld
import org.http4k.ai.model.ModelName
import org.junit.jupiter.api.io.TempDir
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import java.io.File
import java.time.Instant
import kotlin.test.Test

class LabellingTest {

    private val recordedAt = Instant.parse("2026-08-01T00:00:00Z")

    private fun gig(name: String, description: String = "A gig called $name, described at enough length to be judged on its own page text rather than its poster") =
        Gig(
            GigId(theUnderworld.id, GigUrl("https://example.com/$name")),
            GigTitle(name),
            GigDate(2026, 8, 8),
            PosterUrl("https://example.com/$name.jpg"),
            GigDescription(description),
        )

    private val doom = gig("doom")
    private val folk = gig("folk")
    private val sludge = gig("sludge")

    // a log holding each gig once, and one classification of its own for each named as judged
    private fun logOf(dir: File, gigs: List<Gig>, judged: List<Gig> = gigs): GigsLog =
        GigsLog(File(dir, "events.ndjson")).apply {
            append(gigs.map { GigObserved(it, recordedAt) })
            append(judged.map { GigClassified(it.id, recordedAt, Genre.Other, ClassificationSource.LLM, "stub") })
        }

    // each stub answers from a table, which is all the selection asks of a classifier
    private fun saying(vararg genres: Pair<Gig, Genre>): GigClassifier {
        val byId = genres.associate { (gig, genre) -> gig.id to genre }
        return GigClassifier { gig ->
            val genre = byId[gig.id] ?: error("nothing to say about ${gig.title}")
            Classification(genre, ClassificationSource.LLM, ModelName.of("stub"))
        }
    }

    @Test
    fun `offers the gigs the log has judged and the set has not settled`(@TempDir dir: File) {
        val log = logOf(dir, listOf(doom, folk, sludge))

        expectThat(gigsAwaitingLabels(log, emptySet()).map { it.title })
            .containsExactlyInAnyOrder(doom.title, folk.title, sludge.title)
        expectThat(gigsAwaitingLabels(log, setOf(doom.id, sludge.id)).map { it.title })
            .containsExactly(folk.title)
    }

    @Test
    fun `leaves out a gig the log has no verdict of its own for`(@TempDir dir: File) {
        // there is nothing to compare a classifier against on a gig the log has not judged, and it is
        // one the daily run has yet to reach rather than one anybody has an opinion about
        val log = logOf(dir, listOf(doom, folk), judged = listOf(doom))

        expectThat(gigsAwaitingLabels(log, emptySet()).map { it.title }).containsExactly(doom.title)
    }

    @Test
    fun `leaves out a gig whose page said too little to be judged on`(@TempDir dir: File) {
        // that gig went to its poster instead, so asking a text classifier about its description is
        // asking a different question from the one the recorded verdict answers
        val thin = gig("thin", description = "Doors 7pm")
        expectThat(thin.description.value.length < THIN_TEXT_THRESHOLD).isEqualTo(true)
        val log = logOf(dir, listOf(doom, thin))

        expectThat(gigsAwaitingLabels(log, emptySet()).map { it.title }).containsExactly(doom.title)
    }

    @Test
    fun `orders by the gig's own url, so stopping and starting picks up where it left off`(@TempDir dir: File) {
        val log = logOf(dir, listOf(doom, folk, sludge))

        val first = gigsAwaitingLabels(log, emptySet())
        val afterOneIsLabelled = gigsAwaitingLabels(log, setOf(first.first().id))

        expectThat(afterOneIsLabelled).isEqualTo(first.drop(1))
    }

    @Test
    fun `finds only the gigs the classifiers answer differently, and says what each said`() {
        val gigs = listOf(doom, folk, sludge)

        val disagreements = disagreementsAmong(
            gigs,
            listOf(
                "a" to saying(doom to Genre.Metal, folk to Genre.Other, sludge to Genre.Metal),
                "b" to saying(doom to Genre.Metal, folk to Genre.Other, sludge to Genre.Other),
            ),
            wanted = 5,
        )

        expectThat(disagreements.map { it.first }).containsExactly(sludge)
        expectThat(disagreements.single().second).isEqualTo("disagreed - a: Metal, b: Other")
    }

    @Test
    fun `a gig some classifier had no answer for is no disagreement - there is nothing to disagree about`() {
        val disagreements = disagreementsAmong(
            listOf(doom, folk),
            listOf(
                "a" to saying(doom to Genre.Metal, folk to Genre.Other),
                "b" to saying(doom to Genre.Other),
            ),
            wanted = 5,
        )

        expectThat(disagreements.map { it.first }).containsExactly(doom)
    }

    @Test
    fun `stops looking once the batch is full, rather than asking about every gig left`() {
        val asked = mutableListOf<GigTitle>()
        fun counting(classifier: GigClassifier) = GigClassifier { gig ->
            asked += gig.title
            classifier.classify(gig)
        }
        // every gig here is one they answer differently, so a batch of one is filled by the first
        val metal = counting(saying(doom to Genre.Metal, folk to Genre.Metal, sludge to Genre.Metal))
        val other = counting(saying(doom to Genre.Other, folk to Genre.Other, sludge to Genre.Other))

        val disagreements = disagreementsAmong(listOf(doom, folk, sludge), listOf("a" to metal, "b" to other), wanted = 1)

        expectThat(disagreements.map { it.first }).containsExactly(doom)
        // asking is a model call a person is waiting on, so the two the batch didn't need are two
        // that were never made
        expectThat(asked.toSet()).containsExactly(doom.title)
    }

    @Test
    fun `the set keeps what was judged, not a pointer to it`(@TempDir dir: File) {
        val set = LabelledGigs(dir)
        val rows = listOf(
            LabelledGig(doom, Genre.Metal, "doom is metal"),
            LabelledGig(folk, Genre.Other, "a folk night"),
        )

        set.add(rows)

        expectThat(set.all()).containsExactlyInAnyOrder(*rows.toTypedArray())
        expectThat(set.all().map { it.gig.description }).containsExactlyInAnyOrder(doom.description, folk.description)
    }

    @Test
    fun `a row the page cannot answer is kept apart from the rest`(@TempDir dir: File) {
        val set = LabelledGigs(dir)
        val offPage = LabelledGig(doom, Genre.Metal, "known band, the page only says rockers", canBeDerivedPurelyFromText = false)

        set.add(listOf(LabelledGig(folk, Genre.Other, "a folk night"), offPage))

        // it is a row like any other - it is still ground truth - and it survives being written and
        // read back, because what it is for is telling a classifier's failure from the page's silence
        expectThat(set.all().filterNot { it.canBeDerivedPurelyFromText }).containsExactlyInAnyOrder(offPage)
        expectThat(set.all().count { it.canBeDerivedPurelyFromText }).isEqualTo(1)
    }

    @Test
    fun `a gig left out of the set is not one of its rows, and is not offered again`(@TempDir dir: File) {
        val set = LabelledGigs(File(dir, "set"))

        set.add(listOf(LabelledGig(doom, Genre.Metal, "doom is metal")))
        set.exclude(listOf(ExcludedGig(folk, "page names no genre at all")))

        // an exclusion is a gig the set has settled without holding an opinion on, so it counts
        // against being asked again but not towards anything a classifier is scored on
        expectThat(set.all().map { it.gig }).containsExactly(doom)
        expectThat(set.excluded().map { it.gig }).containsExactly(folk)
        expectThat(set.settled()).isEqualTo(setOf(doom.id, folk.id))

        val log = logOf(dir, listOf(doom, folk, sludge))
        expectThat(gigsAwaitingLabels(log, set.settled()).map { it.title }).containsExactly(sludge.title)
    }

    @Test
    fun `a gig lands in the same half however often the set is rebuilt, and both halves get used`() {
        val gigs = (1..200).map { gig("gig-$it") }
        val split = gigs.associateWith(::splitOf)

        expectThat(gigs.map(::splitOf)).isEqualTo(gigs.map { split.getValue(it) })
        expectThat(gigs.count { splitOf(it) == Split.Test } in 1 until gigs.size).isEqualTo(true)
    }

    @Test
    fun `an empty set reads as empty rather than failing`(@TempDir dir: File) {
        expectThat(LabelledGigs(dir).all()).isEmpty()
        expectThat(LabelledGigs(dir).excluded()).isEmpty()
    }
}
