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
            GigClassified(venue = gig.venue, url = gig.url, recordedAt = recordedAt, genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
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
            GigClassified(venue = firstSeen.venue, url = firstSeen.url, recordedAt = Instant.parse("2026-07-10T00:00:00Z"), genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
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
    fun `projects only metal gigs, excluding unclassified and never-classified ones`() {
        val neverClassified = GigEvent(title = "Never Classified", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/never-classified", imageUrl = "")
        val classifiedMetal = GigEvent(title = "Classified Metal", venue = "Test Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/gigs/classified-metal", imageUrl = "")
        val classifiedUnmatched = GigEvent(title = "Classified Unmatched", venue = "Test Venue", year = 2026, month = "Aug", day = "10", url = "https://example.com/gigs/classified-unmatched", imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<GigLogEntry> = listOf(
            GigObserved(neverClassified, recordedAt),
            GigObserved(classifiedMetal, recordedAt),
            GigClassified(classifiedMetal.venue, classifiedMetal.url, recordedAt, genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
            GigObserved(classifiedUnmatched, recordedAt),
            GigClassified(classifiedUnmatched.venue, classifiedUnmatched.url, recordedAt, genre = Genre.Unclassified, source = ClassificationSource.Keywords),
        )

        expectThat(projectMetalGigs(events)).isEqualTo(listOf(classifiedMetal))
    }

    @Test
    fun `projects metal gigs using only the latest classification per gig`() {
        val gig = GigEvent(title = "Reclassified Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/reclassified", imageUrl = "")
        val events: List<GigLogEntry> = listOf(
            GigObserved(gig, Instant.parse("2026-07-01T00:00:00Z")),
            GigClassified(gig.venue, gig.url, Instant.parse("2026-07-01T00:00:00Z"), genre = Genre.Metal, matchedKeywords = listOf("thrash"), source = ClassificationSource.Keywords),
            GigClassified(gig.venue, gig.url, Instant.parse("2026-07-15T00:00:00Z"), genre = Genre.Unclassified, source = ClassificationSource.User),
        )

        expectThat(projectMetalGigs(events)).isEqualTo(emptyList())
    }

    @Test
    fun `projects unclassified gigs, including ones never classified at all`() {
        val neverClassified = GigEvent(title = "Never Classified", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/never-classified", imageUrl = "")
        val classifiedMetal = GigEvent(title = "Classified Metal", venue = "Test Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/gigs/classified-metal", imageUrl = "")
        val classifiedUnmatched = GigEvent(title = "Classified Unmatched", venue = "Test Venue", year = 2026, month = "Aug", day = "10", url = "https://example.com/gigs/classified-unmatched", imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<GigLogEntry> = listOf(
            GigObserved(neverClassified, recordedAt),
            GigObserved(classifiedMetal, recordedAt),
            GigClassified(classifiedMetal.venue, classifiedMetal.url, recordedAt, genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
            GigObserved(classifiedUnmatched, recordedAt),
            GigClassified(classifiedUnmatched.venue, classifiedUnmatched.url, recordedAt, genre = Genre.Unclassified, source = ClassificationSource.Keywords),
        )

        expectThat(projectUnclassifiedGigs(events)).containsExactlyInAnyOrder(neverClassified, classifiedUnmatched)
    }

    @Test
    fun `projects unclassified gigs using only the latest classification per gig`() {
        val gig = GigEvent(title = "Reclassified Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/reclassified", imageUrl = "")
        val events: List<GigLogEntry> = listOf(
            GigObserved(gig, Instant.parse("2026-07-01T00:00:00Z")),
            GigClassified(gig.venue, gig.url, Instant.parse("2026-07-01T00:00:00Z"), genre = Genre.Unclassified, source = ClassificationSource.Keywords),
            GigClassified(gig.venue, gig.url, Instant.parse("2026-07-15T00:00:00Z"), genre = Genre.Metal, matchedKeywords = listOf("thrash"), source = ClassificationSource.Keywords),
        )

        expectThat(projectUnclassifiedGigs(events)).isEqualTo(emptyList())
    }

    @Test
    fun `a later user override takes precedence over an earlier keyword classification`() {
        val gig = GigEvent(title = "Overridden Gig", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/overridden", imageUrl = "")
        val events: List<GigLogEntry> = listOf(
            GigObserved(gig, Instant.parse("2026-07-01T00:00:00Z")),
            GigClassified(gig.venue, gig.url, Instant.parse("2026-07-01T00:00:00Z"), genre = Genre.Unclassified, source = ClassificationSource.Keywords),
            GigClassified(gig.venue, gig.url, Instant.parse("2026-07-15T00:00:00Z"), genre = Genre.Metal, source = ClassificationSource.User),
        )

        expectThat(projectUnclassifiedGigs(events)).isEqualTo(emptyList())
    }
}
