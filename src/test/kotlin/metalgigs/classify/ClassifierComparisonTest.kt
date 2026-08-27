package metalgigs.classify

import metalgigs.ClassificationSource
import metalgigs.Genre
import metalgigs.Gig
import metalgigs.GigClassified
import metalgigs.GigDate
import metalgigs.GigDescription
import metalgigs.GigId
import metalgigs.GigTitle
import metalgigs.GigUrl
import metalgigs.PosterUrl
import metalgigs.scrape.venues.theUnderworld
import strikt.api.expectThat
import strikt.assertions.contains
import java.time.Instant
import kotlin.test.Test

class ClassifierComparisonTest {

    private val recordedAt = Instant.parse("2026-08-01T00:00:00Z")

    private fun gig(name: String) = Gig(
        GigId(theUnderworld.id, GigUrl("https://example.com/$name")),
        GigTitle(name),
        GigDate(2026, 8, 8),
        PosterUrl("https://example.com/$name.jpg"),
        GigDescription("A gig called $name"),
    )

    // Each stub answers from a table of what it thinks of each gig, which is all the harness asks of
    // a classifier - so a comparison can be set up without a model, a prompt or a network.
    private fun saying(vararg genres: Pair<String, Genre>): GigClassifier {
        val byName = genres.toMap()
        return GigClassifier { gig ->
            val genre = byName[gig.title.value] ?: error("nothing to say about ${gig.title}")
            GigClassified(gig.id, recordedAt, genre, ClassificationSource.LLM, "stub", inputTokens = 10, outputTokens = 1)
        }
    }

    private val gigs = listOf("doom", "folk", "sludge").map(::gig)

    @Test
    fun `scores each candidate against the reference`() {
        val report = compareClassifiers(
            gigs,
            "log" to saying("doom" to Genre.Metal, "folk" to Genre.Other, "sludge" to Genre.Metal),
            "agrees" to saying("doom" to Genre.Metal, "folk" to Genre.Other, "sludge" to Genre.Metal),
            "misses one" to saying("doom" to Genre.Metal, "folk" to Genre.Other, "sludge" to Genre.Other),
        )

        expectThat(report) {
            contains("## agrees vs log")
            contains("3 gig(s) both answered, 3 agreed (100%).")
            contains("## misses one vs log")
            contains("3 gig(s) both answered, 2 agreed (66%).")
            // the two ways of being wrong read differently on the page, so they are counted apart
            contains("Of 2 gig(s) log calls Metal, misses one finds 1 (50%), and adds 0 more it doesn't.")
        }
    }

    @Test
    fun `lists the gigs the classifiers don't all answer the same way`() {
        val report = compareClassifiers(
            gigs,
            "log" to saying("doom" to Genre.Metal, "folk" to Genre.Other, "sludge" to Genre.Metal),
            "a" to saying("doom" to Genre.Metal, "folk" to Genre.Other, "sludge" to Genre.Other),
            "b" to saying("doom" to Genre.Metal, "folk" to Genre.Metal, "sludge" to Genre.Metal),
        )

        expectThat(report) {
            contains("## Where they disagree (2 of 3)")
            contains("<https://example.com/sludge>")
            contains("<https://example.com/folk>")
            // the one they all called the same way isn't a choice between them, so it isn't listed
            not { contains("<https://example.com/doom>") }
        }
    }

    @Test
    fun `a gig a classifier fails on is left out of the scoring rather than counted against it`() {
        val report = compareClassifiers(
            gigs,
            "log" to saying("doom" to Genre.Metal, "folk" to Genre.Other, "sludge" to Genre.Metal),
            "silent on one" to saying("doom" to Genre.Metal, "folk" to Genre.Other),
        )

        expectThat(report) {
            // two answered, both agreed - the third is reported as a gap, not as a wrong answer
            contains("2 gig(s) both answered, 2 agreed (100%).")
            contains("## silent on one had no answer for 1 gig(s)")
            contains("nothing to say about sludge")
        }
    }

    @Test
    fun `says so when nothing separates them`() {
        val same = { saying("doom" to Genre.Metal, "folk" to Genre.Other, "sludge" to Genre.Metal) }

        val report = compareClassifiers(gigs, "log" to same(), "a" to same(), "b" to same())

        expectThat(report).contains("## They agreed on all 3 gig(s) they all answered")
    }
}
