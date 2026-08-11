import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import java.io.File
import java.time.Instant
import kotlin.test.Test

class GigsStoreTest {

    @Test
    fun `appends and reads back gig log entries of different kinds`() {
        val gig = GigEvent(title = "Test Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/test-gig", imageUrl = "https://example.com/images/test-gig.jpg")
        val recordedAt = Instant.parse("2026-08-01T12:00:00Z")
        val entries: List<GigLogEntry> = listOf(
            GigObserved(gig, recordedAt),
            GigClassified(gig.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
        )
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }

        appendGigLogEntries(file, entries)

        expectThat(readGigLogEntries(file)).isEqualTo(entries)
    }

    @Test
    fun `projects the latest observation per gig, ignoring classification entries`() {
        val firstSeen = GigEvent(title = "Some Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/some-gig", imageUrl = "https://example.com/images/some-gig.jpg")
        val soldOut = firstSeen.copy(title = "Some Gig - SOLD OUT")
        val events: List<GigLogEntry> = listOf(
            GigObserved(firstSeen, Instant.parse("2026-07-01T00:00:00Z")),
            GigClassified(firstSeen.id, Instant.parse("2026-07-10T00:00:00Z"), Genre.Metal, ClassificationSource.LLM),
            GigObserved(soldOut, Instant.parse("2026-07-15T00:00:00Z")),
        )

        expectThat(projectCurrentGigs(events)).isEqualTo(listOf(soldOut))
    }

    @Test
    fun `keeps separate gigs from different venues distinct`() {
        val gigA = GigEvent(title = "Gig A", venue = "Venue A", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/same-slug", imageUrl = "https://example.com/images/gig-a.jpg")
        val gigB = GigEvent(title = "Gig B", venue = "Venue B", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/same-slug", imageUrl = "https://example.com/images/gig-b.jpg")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events = listOf(GigObserved(gigA, recordedAt), GigObserved(gigB, recordedAt))

        expectThat(projectCurrentGigs(events)).containsExactlyInAnyOrder(gigA, gigB)
    }

    @Test
    fun `treats a gig as new or changed if it's unseen or differs from its latest observation`() {
        val unseen = GigEvent(title = "Unseen Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/unseen", imageUrl = "")
        val unchangedGig = GigEvent(title = "Unchanged Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/gigs/unchanged", imageUrl = "")
        val soldOutBefore = GigEvent(title = "Changed Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "10", url = "https://example.com/gigs/changed", imageUrl = "")
        val soldOutNow = soldOutBefore.copy(title = "Changed Gig - SOLD OUT")
        val existingEntries: List<GigLogEntry> = listOf(
            GigObserved(unchangedGig, Instant.parse("2026-07-01T00:00:00Z")),
            GigObserved(soldOutBefore, Instant.parse("2026-07-01T00:00:00Z")),
        )

        val newOrChanged = newOrChangedGigs(existingEntries, scrapedGigs = listOf(unseen, unchangedGig, soldOutNow))

        expectThat(newOrChanged).containsExactlyInAnyOrder(unseen, soldOutNow)
    }

    @Test
    fun `treats a gig as changed again if it reverts to a state seen earlier than its latest observation`() {
        val original = GigEvent(title = "Reverting Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/reverting", imageUrl = "")
        val soldOut = original.copy(title = "Reverting Gig - SOLD OUT")
        val existingEntries: List<GigLogEntry> = listOf(
            GigObserved(original, Instant.parse("2026-07-01T00:00:00Z")),
            GigObserved(soldOut, Instant.parse("2026-07-10T00:00:00Z")),
        )

        val newOrChanged = newOrChangedGigs(existingEntries, scrapedGigs = listOf(original))

        expectThat(newOrChanged).containsExactly(original)
    }

    @Test
    fun `derives last-scraped time per venue from the latest observation, ignoring venues never observed`() {
        val gigA1 = GigEvent(title = "Gig A1", venue = "Venue A", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/a1", imageUrl = "")
        val gigA2 = GigEvent(title = "Gig A2", venue = "Venue A", year = 2026, month = "Aug", day = "09", url = "https://example.com/gigs/a2", imageUrl = "")
        val gigB = GigEvent(title = "Gig B", venue = "Venue B", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/b", imageUrl = "")
        val events: List<GigLogEntry> = listOf(
            GigObserved(gigA1, Instant.parse("2026-07-01T00:00:00Z")),
            GigObserved(gigA2, Instant.parse("2026-07-10T00:00:00Z")),
            GigObserved(gigB, Instant.parse("2026-07-05T00:00:00Z")),
        )

        val lastScrapedAt = lastScrapedAt(events)

        expectThat(lastScrapedAt).isEqualTo(
            mapOf("Venue A" to Instant.parse("2026-07-10T00:00:00Z"), "Venue B" to Instant.parse("2026-07-05T00:00:00Z")),
        )
        expectThat(lastScrapedAt["Venue Never Scraped"]).isEqualTo(null)
    }

    @Test
    fun `detects an already-ingested poster by its gigs' shared source-url prefix`() {
        val gig = GigEvent(title = "Doom Night", venue = "Some Venue", year = 2026, month = "Aug", day = "14", url = "https://example.com/post/1#gig-doom-night-2026-08-14", imageUrl = "")
        val events: List<GigLogEntry> = listOf(GigObserved(gig, Instant.parse("2026-07-01T00:00:00Z")))

        expectThat(alreadyIngested(events, "https://example.com/post/1")).isEqualTo(true)
        expectThat(alreadyIngested(events, "https://example.com/post/2")).isEqualTo(false)
    }

    @Test
    fun `projects only gigs classified Metal, excluding never-classified ones`() {
        val neverClassified = GigEvent(title = "Never Classified", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/never-classified", imageUrl = "")
        val metal = GigEvent(title = "Metal", venue = "Test Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/gigs/metal", imageUrl = "")
        val other = GigEvent(title = "Other", venue = "Test Venue", year = 2026, month = "Aug", day = "10", url = "https://example.com/gigs/other", imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<GigLogEntry> = listOf(
            GigObserved(neverClassified, recordedAt),
            GigObserved(metal, recordedAt),
            GigClassified(metal.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigObserved(other, recordedAt),
            GigClassified(other.id, recordedAt, Genre.Other, ClassificationSource.LLM),
        )

        expectThat(projectMetalGigs(events)).isEqualTo(listOf(metal))
    }

    @Test
    fun `computes classification status per gig, using the latest classification`() {
        val classified = GigEvent(title = "Classified", venue = "Test Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/gigs/classified", imageUrl = "")
        val reclassified = GigEvent(title = "Reclassified", venue = "Test Venue", year = 2026, month = "Aug", day = "10", url = "https://example.com/gigs/reclassified", imageUrl = "")
        val neverClassified = GigEvent(title = "Never Classified", venue = "Test Venue", year = 2026, month = "Aug", day = "11", url = "https://example.com/gigs/never-classified", imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<GigLogEntry> = listOf(
            GigObserved(classified, recordedAt),
            GigClassified(classified.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigObserved(reclassified, recordedAt),
            GigClassified(reclassified.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigClassified(reclassified.id, Instant.parse("2026-07-15T00:00:00Z"), Genre.Other, ClassificationSource.LLM),
            GigObserved(neverClassified, recordedAt),
        )

        val statusByGig = classificationStatusByGig(events)

        expectThat(statusByGig[classified.id]).isEqualTo(ClassificationStatus.Classified(Genre.Metal))
        expectThat(statusByGig[reclassified.id]).isEqualTo(ClassificationStatus.Classified(Genre.Other))
        expectThat(statusByGig[neverClassified.id]).isEqualTo(null)
    }

    @Test
    fun `a user override always beats the classifier's verdict`() {
        val overriddenToMetal = GigEvent(title = "Overridden To Metal", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/overridden-to-metal", imageUrl = "")
        val overriddenToOther = GigEvent(title = "Overridden To Other", venue = "Test Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/gigs/overridden-to-other", imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<GigLogEntry> = listOf(
            GigObserved(overriddenToMetal, recordedAt),
            GigClassified(overriddenToMetal.id, recordedAt, Genre.Other, ClassificationSource.LLM),
            GigClassified(overriddenToMetal.id, recordedAt, Genre.Metal, ClassificationSource.User),
            GigObserved(overriddenToOther, recordedAt),
            GigClassified(overriddenToOther.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigClassified(overriddenToOther.id, recordedAt, Genre.Other, ClassificationSource.User),
        )

        expectThat(projectMetalGigs(events)).isEqualTo(listOf(overriddenToMetal))
        expectThat(classificationStatusByGig(events)[overriddenToOther.id]).isEqualTo(ClassificationStatus.Classified(Genre.Other))
    }

    @Test
    fun `treats any classification as already-classified, whoever made it`() {
        val classified = GigEvent(title = "Classified", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/classified", imageUrl = "")
        val userOverridden = GigEvent(title = "User Overridden", venue = "Test Venue", year = 2026, month = "Aug", day = "10", url = "https://example.com/gigs/user-overridden", imageUrl = "")
        val neverClassified = GigEvent(title = "Never Classified", venue = "Test Venue", year = 2026, month = "Aug", day = "11", url = "https://example.com/gigs/never-classified", imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<GigLogEntry> = listOf(
            GigObserved(classified, recordedAt),
            GigClassified(classified.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigObserved(userOverridden, recordedAt),
            GigClassified(userOverridden.id, recordedAt, Genre.Metal, ClassificationSource.User),
            GigObserved(neverClassified, recordedAt),
        )

        expectThat(alreadyClassified(events)).containsExactlyInAnyOrder(classified.id, userOverridden.id)
    }
}
