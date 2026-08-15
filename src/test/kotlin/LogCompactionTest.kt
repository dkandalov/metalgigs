import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test

class LogCompactionTest {

    private val gigA = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/a"), title = "Gig A", date = LocalDate.of(2026, 8, 8), imageUrl = "", description = "")
    private val gigB = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/b"), title = "Gig B", date = LocalDate.of(2026, 8, 9), imageUrl = "", description = "")

    private fun at(day: Int) = Instant.parse("2026-08-0${day}T12:00:00Z")

    private fun gigsLog(entries: List<LogEntry>): GigsLog {
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        return GigsLog(file).apply { append(entries) }
    }

    @Test
    fun `keeps only the latest observation of each gig`() {
        val soldOut = gigA.copy(title = "Gig A - SOLD OUT")
        val entries = listOf(
            GigObserved(gigA, at(1)),
            GigObserved(gigB, at(1)),
            GigObserved(soldOut, at(3)),
        )

        expectThat(gigsLog(entries).compact().entries).containsExactly(
            GigObserved(gigB, at(1)),
            GigObserved(soldOut, at(3)),
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
            GigClassified(gigA.id, at(2), Genre.Metal, ClassificationSource.User),
        )
    }

    @Test
    fun `keeps the latest LLM classification when the user has not overridden`() {
        val entries = listOf(
            GigClassified(gigA.id, at(1), Genre.Other, ClassificationSource.LLM),
            GigClassified(gigA.id, at(3), Genre.Metal, ClassificationSource.LLM),
        )

        expectThat(gigsLog(entries).compact().entries).containsExactly(
            GigClassified(gigA.id, at(3), Genre.Metal, ClassificationSource.LLM),
        )
    }

    // they record what was published rather than what a gig is, and there is one per render
    @Test
    fun `keeps every render entry`() {
        val entries = listOf(
            GigsRendered("2026-08-01T12-00-00Z.html", gigCount = 1, logicalDate = LocalDate.of(2026, 8, 1), recordedAt = at(1)),
            GigsRendered("2026-08-02T12-00-00Z.html", gigCount = 2, logicalDate = LocalDate.of(2026, 8, 2), recordedAt = at(2)),
        )

        expectThat(gigsLog(entries).compact().entries).isEqualTo(entries)
    }

    @Test
    fun `leaves an already-compact log alone, in recorded order`() {
        val entries = listOf(
            GigObserved(gigA, at(1)),
            GigClassified(gigA.id, at(2), Genre.Metal, ClassificationSource.LLM),
            GigsRendered("2026-08-03T12-00-00Z.html", gigCount = 1, logicalDate = LocalDate.of(2026, 8, 3), recordedAt = at(3)),
        )

        expectThat(gigsLog(entries).compact().entries).isEqualTo(entries)
    }

    @Test
    fun `projects the same gigs and genres as the log it replaces`() {
        val entries = listOf(
            GigObserved(gigA, at(1)),
            GigObserved(gigB, at(1)),
            GigClassified(gigA.id, at(1), Genre.Other, ClassificationSource.LLM),
            GigObserved(gigA.copy(title = "Gig A - SOLD OUT"), at(2)),
            GigClassified(gigA.id, at(2), Genre.Metal, ClassificationSource.User),
            GigClassified(gigB.id, at(2), Genre.Other, ClassificationSource.LLM),
            GigsRendered("2026-08-03T12-00-00Z.html", gigCount = 1, logicalDate = LocalDate.of(2026, 8, 3), recordedAt = at(3)),
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
}
