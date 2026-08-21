package metalgigs

import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test

class LogCompactionTest {

    private val gigA = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/a")), GigTitle("Gig A"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
    private val gigB = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/b")), GigTitle("Gig B"), GigDate(2026, 8, 9), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))

    private fun at(day: Int) = Instant.parse("2026-08-0${day}T12:00:00Z")

    private fun gigsLog(entries: List<LogEntry>): GigsLog {
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        return GigsLog(file).apply { append(entries) }
    }

    @Test
    fun `keeps only the latest observation of each gig`() {
        val soldOut = gigA.copy(title = GigTitle("Gig A - SOLD OUT"))
        val entries = listOf(
            GigObserved(gigA, at(1)),
            GigObserved(gigB, at(1)),
            GigObserved(soldOut, at(3)),
        )

        expectThat(gigsLog(entries).compact().entries).containsExactly(
            GigObserved(gigB, at(1), seq = 1),
            GigObserved(soldOut, at(3), seq = 2),
        )
    }

    // the trap: keeping the single newest classification would drop the override and flip the genre,
    // since a later LLM run judges a gig the user has already settled
    @Test
    fun `keeps a user override over an LLM classification recorded after it`() {
        val entries = listOf(
            GigClassified(gigA.id, at(1), Genre.Other, ClassificationSource.LLM),
            GigClassified(gigA.id, at(2), Genre.Metal, ClassificationSource.User),
            GigClassified(gigA.id, at(3), Genre.Other, ClassificationSource.LLM),
        )

        expectThat(gigsLog(entries).compact().entries).containsExactly(
            GigClassified(gigA.id, at(2), Genre.Metal, ClassificationSource.User, seq = 1),
        )
    }

    @Test
    fun `keeps the latest LLM classification when the user has not overridden`() {
        val entries = listOf(
            GigClassified(gigA.id, at(1), Genre.Other, ClassificationSource.LLM),
            GigClassified(gigA.id, at(3), Genre.Metal, ClassificationSource.LLM),
        )

        expectThat(gigsLog(entries).compact().entries).containsExactly(
            GigClassified(gigA.id, at(3), Genre.Metal, ClassificationSource.LLM, seq = 1),
        )
    }

    // they record what was published rather than what a gig is, and there is one per render
    @Test
    fun `keeps every render entry`() {
        val entries = listOf(
            GigsRendered("2026-08-01T12-00-00Z.html", 1, LocalDate.of(2026, 8, 1), at(1)),
            GigsRendered("2026-08-02T12-00-00Z.html", 2, LocalDate.of(2026, 8, 2), at(2)),
        )
        val log = gigsLog(entries)

        expectThat(log.compact().entries).isEqualTo(log.entries)
    }

    @Test
    fun `leaves an already-compact log alone, in recorded order`() {
        val entries = listOf(
            GigObserved(gigA, at(1)),
            GigClassified(gigA.id, at(2), Genre.Metal, ClassificationSource.LLM),
            GigsRendered("2026-08-03T12-00-00Z.html", 1, LocalDate.of(2026, 8, 3), at(3)),
        )
        val log = gigsLog(entries)

        expectThat(log.compact().entries).isEqualTo(log.entries)
    }

    @Test
    fun `projects the same gigs and genres as the log it replaces`() {
        val entries = listOf(
            GigObserved(gigA, at(1)),
            GigObserved(gigB, at(1)),
            GigClassified(gigA.id, at(1), Genre.Other, ClassificationSource.LLM),
            GigObserved(gigA.copy(title = GigTitle("Gig A - SOLD OUT")), at(2)),
            GigClassified(gigA.id, at(2), Genre.Metal, ClassificationSource.User),
            GigClassified(gigB.id, at(2), Genre.Other, ClassificationSource.LLM),
            GigsRendered("2026-08-03T12-00-00Z.html", 1, LocalDate.of(2026, 8, 3), at(3)),
        )
        val before = gigsLog(entries)
        val after = gigsLog(before.compact().entries)

        expectThat(after.currentGigs().toSet()).isEqualTo(before.currentGigs().toSet())
        expectThat(after.classificationStatus()).isEqualTo(before.classificationStatus())
        expectThat(after.metalGigs().toSet()).isEqualTo(before.metalGigs().toSet())
        expectThat(after.alreadyClassified()).isEqualTo(before.alreadyClassified())
        expectThat(after.lastScrapedAt()).isEqualTo(before.lastScrapedAt())
        expectThat(after.alreadyRenderedFor(LocalDate.of(2026, 8, 3))).isEqualTo(true)
    }
    // a replacement says what a venue did rather than what a gig is, so there is nothing to
    // supersede it - and dropping one would put the gig at the old url back on the page
    @Test
    fun `keeps every replacement entry, and the observation of the gig that moved`() {
        val entries = listOf(
            GigObserved(gigA, at(1)),
            GigObserved(gigB, at(1)),
            GigReplaced(gigA.id, gigB.id, at(2)),
        )

        val compacted = gigsLog(entries).compact()

        expectThat(compacted.entries).containsExactly(
            GigObserved(gigA, at(1), seq = 0),
            GigObserved(gigB, at(1), seq = 1),
            GigReplaced(gigA.id, gigB.id, at(2), seq = 2),
        )
        expectThat(gigsLog(compacted.entries).currentGigs()).isEqualTo(listOf(gigB))
    }
}
