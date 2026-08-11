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
    fun `projects metal gigs only where Keywords and LLM agree`() {
        val neverClassified = GigEvent(title = "Never Classified", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/never-classified", imageUrl = "")
        val agreedMetal = GigEvent(title = "Agreed Metal", venue = "Test Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/gigs/agreed-metal", imageUrl = "")
        val agreedOther = GigEvent(title = "Agreed Other", venue = "Test Venue", year = 2026, month = "Aug", day = "10", url = "https://example.com/gigs/agreed-other", imageUrl = "")
        val pendingLLM = GigEvent(title = "Pending LLM", venue = "Test Venue", year = 2026, month = "Aug", day = "11", url = "https://example.com/gigs/pending-llm", imageUrl = "")
        val disputed = GigEvent(title = "Disputed", venue = "Test Venue", year = 2026, month = "Aug", day = "12", url = "https://example.com/gigs/disputed", imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<GigLogEntry> = listOf(
            GigObserved(neverClassified, recordedAt),
            GigObserved(agreedMetal, recordedAt),
            GigClassified(agreedMetal.venue, agreedMetal.url, recordedAt, genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
            GigClassified(agreedMetal.venue, agreedMetal.url, recordedAt, genre = Genre.Metal, source = ClassificationSource.LLM),
            GigObserved(agreedOther, recordedAt),
            GigClassified(agreedOther.venue, agreedOther.url, recordedAt, genre = Genre.Other, source = ClassificationSource.Keywords),
            GigClassified(agreedOther.venue, agreedOther.url, recordedAt, genre = Genre.Other, source = ClassificationSource.LLM),
            GigObserved(pendingLLM, recordedAt),
            GigClassified(pendingLLM.venue, pendingLLM.url, recordedAt, genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
            GigObserved(disputed, recordedAt),
            GigClassified(disputed.venue, disputed.url, recordedAt, genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
            GigClassified(disputed.venue, disputed.url, recordedAt, genre = Genre.Other, source = ClassificationSource.LLM),
        )

        expectThat(projectMetalGigs(events)).isEqualTo(listOf(agreedMetal))
    }

    @Test
    fun `computes classification status per gig - agreed, pending, or disputed`() {
        val agreedMetal = GigEvent(title = "Agreed Metal", venue = "Test Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/gigs/agreed-metal", imageUrl = "")
        val agreedOther = GigEvent(title = "Agreed Other", venue = "Test Venue", year = 2026, month = "Aug", day = "10", url = "https://example.com/gigs/agreed-other", imageUrl = "")
        val pendingLLM = GigEvent(title = "Pending LLM", venue = "Test Venue", year = 2026, month = "Aug", day = "11", url = "https://example.com/gigs/pending-llm", imageUrl = "")
        val disputed = GigEvent(title = "Disputed", venue = "Test Venue", year = 2026, month = "Aug", day = "12", url = "https://example.com/gigs/disputed", imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<GigLogEntry> = listOf(
            GigObserved(agreedMetal, recordedAt),
            GigClassified(agreedMetal.venue, agreedMetal.url, recordedAt, genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
            GigClassified(agreedMetal.venue, agreedMetal.url, recordedAt, genre = Genre.Metal, source = ClassificationSource.LLM),
            GigObserved(agreedOther, recordedAt),
            GigClassified(agreedOther.venue, agreedOther.url, recordedAt, genre = Genre.Other, source = ClassificationSource.Keywords),
            GigClassified(agreedOther.venue, agreedOther.url, recordedAt, genre = Genre.Other, source = ClassificationSource.LLM),
            GigObserved(pendingLLM, recordedAt),
            GigClassified(pendingLLM.venue, pendingLLM.url, recordedAt, genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
            GigObserved(disputed, recordedAt),
            GigClassified(disputed.venue, disputed.url, recordedAt, genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
            GigClassified(disputed.venue, disputed.url, recordedAt, genre = Genre.Other, source = ClassificationSource.LLM),
        )

        val statusByGig = classificationStatusByGig(events)

        expectThat(statusByGig[agreedMetal.id]).isEqualTo(ClassificationStatus.Classified(Genre.Metal))
        expectThat(statusByGig[agreedOther.id]).isEqualTo(ClassificationStatus.Classified(Genre.Other))
        expectThat(statusByGig[pendingLLM.id]).isEqualTo(ClassificationStatus.Pending)
        expectThat(statusByGig[disputed.id]).isEqualTo(ClassificationStatus.Disputed)
    }

    @Test
    fun `a user override is always final regardless of Keywords or LLM agreement`() {
        val overriddenToMetal = GigEvent(title = "Overridden To Metal", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/overridden-to-metal", imageUrl = "")
        val overriddenToOther = GigEvent(title = "Overridden To Other", venue = "Test Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/gigs/overridden-to-other", imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<GigLogEntry> = listOf(
            GigObserved(overriddenToMetal, recordedAt),
            GigClassified(overriddenToMetal.venue, overriddenToMetal.url, recordedAt, genre = Genre.Other, source = ClassificationSource.Keywords),
            GigClassified(overriddenToMetal.venue, overriddenToMetal.url, recordedAt, genre = Genre.Metal, source = ClassificationSource.User),
            GigObserved(overriddenToOther, recordedAt),
            GigClassified(overriddenToOther.venue, overriddenToOther.url, recordedAt, genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
            GigClassified(overriddenToOther.venue, overriddenToOther.url, recordedAt, genre = Genre.Metal, source = ClassificationSource.LLM),
            GigClassified(overriddenToOther.venue, overriddenToOther.url, recordedAt, genre = Genre.Other, source = ClassificationSource.User),
        )

        expectThat(projectMetalGigs(events)).isEqualTo(listOf(overriddenToMetal))
        expectThat(classificationStatusByGig(events)[overriddenToOther.id]).isEqualTo(ClassificationStatus.Classified(Genre.Other))
    }

    @Test
    fun `tracks already-classified gigs per automated source, treating a user override as settled for both`() {
        val keywordsOnly = GigEvent(title = "Keywords Only", venue = "Test Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gigs/keywords-only", imageUrl = "")
        val userOverridden = GigEvent(title = "User Overridden", venue = "Test Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/gigs/user-overridden", imageUrl = "")
        val neverClassified = GigEvent(title = "Never Classified", venue = "Test Venue", year = 2026, month = "Aug", day = "10", url = "https://example.com/gigs/never-classified", imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<GigLogEntry> = listOf(
            GigObserved(keywordsOnly, recordedAt),
            GigClassified(keywordsOnly.venue, keywordsOnly.url, recordedAt, genre = Genre.Metal, matchedKeywords = listOf("doom"), source = ClassificationSource.Keywords),
            GigObserved(userOverridden, recordedAt),
            GigClassified(userOverridden.venue, userOverridden.url, recordedAt, genre = Genre.Metal, source = ClassificationSource.User),
            GigObserved(neverClassified, recordedAt),
        )

        expectThat(alreadyClassifiedBy(events, ClassificationSource.Keywords))
            .containsExactlyInAnyOrder(keywordsOnly.id, userOverridden.id)
        expectThat(alreadyClassifiedBy(events, ClassificationSource.LLM))
            .containsExactlyInAnyOrder(userOverridden.id)
    }
}
