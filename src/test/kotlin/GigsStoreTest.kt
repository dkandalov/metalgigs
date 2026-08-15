import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test

class GigsStoreTest {

    private fun gigsLog(entries: List<LogEntry>): GigsLog {
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        return GigsLog(file).apply { append(entries) }
    }

    @Test
    fun `appends and reads back gig log entries of different kinds`() {
        val gig = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/test-gig"), title = GigTitle("Test Gig"), date = LocalDate.of(2026, 8, 8), imageUrl = "https://example.com/images/test-gig.jpg", description = "")
        val recordedAt = Instant.parse("2026-08-01T12:00:00Z")
        val entries: List<LogEntry> = listOf(
            GigObserved(gig, recordedAt),
            GigClassified(gig.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigsRendered("2026-08-01T12-00-00Z.html", gigCount = 1, logicalDate = LocalDate.of(2026, 8, 1), recordedAt = recordedAt),
        )
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }

        GigsLog(file).append(entries)

        expectThat(GigsLog(file).entries).isEqualTo(entries)
    }

    @Test
    fun `writes a render entry with its logical date as a plain ISO date`() {
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        val rendered = GigsRendered(
            file = "2026-08-01T12-00-00Z.html",
            gigCount = 42,
            logicalDate = LocalDate.of(2026, 8, 1),
            recordedAt = Instant.parse("2026-08-01T12:00:00Z"),
        )

        GigsLog(file).append(listOf(rendered))

        expectThat(file.readText().trim()).isEqualTo(
            """{"_type": "rendered", "file": "2026-08-01T12-00-00Z.html", "gigCount": 42, "logicalDate": "2026-08-01", "recordedAt": "2026-08-01T12:00:00Z"}""",
        )
    }

    @Test
    fun `round-trips a classification recording which model judged it and whether it saw the poster`() {
        val recordedAt = Instant.parse("2026-08-01T12:00:00Z")
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        val classified = GigClassified(
            id = GigId(VenueId("Test Venue"), "https://example.com/gigs/test-gig"),
            recordedAt = recordedAt,
            genre = Genre.Metal,
            source = ClassificationSource.LLM,
            llmModel = "claude-haiku-4-5-20251001",
            useVision = false,
        )

        GigsLog(file).append(listOf(classified))

        expectThat(GigsLog(file).entries).isEqualTo(listOf(classified))
    }

    // verbatim from the real log, from before llmModel and useVision were added - no keys for
    // either, not null-valued keys, so this is what an absent JFieldMaybe actually has to handle
    @Test
    fun `reads back a classification written before llmModel and useVision existed`() {
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        file.writeText(
            """{"_type": "classified", "venue": "Signature Brew Blackhorse Road", "url": "https://tixr.com/e/187182", "recordedAt": "2026-08-11T21:20:43.785398Z", "genre": "Other", "source": "LLM"}""" + "\n",
        )

        expectThat(GigsLog(file).entries).isEqualTo(
            listOf(
                GigClassified(
                    id = GigId(VenueId("Signature Brew Blackhorse Road"), "https://tixr.com/e/187182"),
                    recordedAt = Instant.parse("2026-08-11T21:20:43.785398Z"),
                    genre = Genre.Other,
                    source = ClassificationSource.LLM,
                ),
            ),
        )
    }

    @Test
    fun `reads back an observation written before the description existed, and one with it`() {
        val gig = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/test-gig"), title = GigTitle("Test Gig"), date = LocalDate.of(2026, 8, 8), imageUrl = "", description = "")
        val recordedAt = Instant.parse("2026-08-01T12:00:00Z")
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        // No description key at all, which no line in the log currently has - this pins the optional
        // read, so a hand-edited or truncated line degrades to "" instead of failing the whole read.
        file.writeText("""{"_type": "observed", "gig": {"title": "Test Gig", "venue": "Test Venue", "date": "2026-08-08", "url": "https://example.com/gigs/test-gig", "imageUrl": ""}, "recordedAt": "2026-08-01T12:00:00Z"}""" + "\n")

        GigsLog(file).append(listOf(GigObserved(gig.copy(description = "Doom metal night"), recordedAt.plusSeconds(60))))

        expectThat(GigsLog(file).entries).isEqualTo(
            listOf(
                GigObserved(gig, recordedAt),
                GigObserved(gig.copy(description = "Doom metal night"), recordedAt.plusSeconds(60)),
            ),
        )
    }

    @Test
    fun `a changed description makes a gig count as changed`() {
        val gig = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/some-gig"), title = GigTitle("Some Gig"), date = LocalDate.of(2026, 8, 8), imageUrl = "", description = "3 tickets left")
        val existing: List<LogEntry> = listOf(GigObserved(gig, Instant.parse("2026-07-01T00:00:00Z")))
        val log = gigsLog(existing)

        val changed = gig.copy(description = "2 tickets left")
        expectThat(log.newOrChangedGigs(listOf(changed))).containsExactly(changed)
        expectThat(log.newOrChangedGigs(listOf(gig))).isEqualTo(emptyList())
    }

    @Test
    fun `projects the latest observation per gig, ignoring classification entries`() {
        val firstSeen = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/some-gig"), title = GigTitle("Some Gig"), date = LocalDate.of(2026, 8, 8), imageUrl = "https://example.com/images/some-gig.jpg", description = "")
        val soldOut = firstSeen.copy(title = GigTitle("Some Gig - SOLD OUT"))
        val events: List<LogEntry> = listOf(
            GigObserved(firstSeen, Instant.parse("2026-07-01T00:00:00Z")),
            GigClassified(firstSeen.id, Instant.parse("2026-07-10T00:00:00Z"), Genre.Metal, ClassificationSource.LLM),
            GigObserved(soldOut, Instant.parse("2026-07-15T00:00:00Z")),
        )

        expectThat(gigsLog(events).currentGigs()).isEqualTo(listOf(soldOut))
    }

    @Test
    fun `keeps separate gigs from different venues distinct`() {
        val gigA = Gig(id = GigId(VenueId("Venue A"), "https://example.com/gigs/same-slug"), title = GigTitle("Gig A"), date = LocalDate.of(2026, 8, 8), imageUrl = "https://example.com/images/gig-a.jpg", description = "")
        val gigB = Gig(id = GigId(VenueId("Venue B"), "https://example.com/gigs/same-slug"), title = GigTitle("Gig B"), date = LocalDate.of(2026, 8, 8), imageUrl = "https://example.com/images/gig-b.jpg", description = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events = listOf(GigObserved(gigA, recordedAt), GigObserved(gigB, recordedAt))

        expectThat(gigsLog(events).currentGigs()).containsExactlyInAnyOrder(gigA, gigB)
    }

    @Test
    fun `treats a gig as new or changed if it's unseen or differs from its latest observation`() {
        val unseen = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/unseen"), title = GigTitle("Unseen Gig"), date = LocalDate.of(2026, 8, 8), imageUrl = "", description = "")
        val unchangedGig = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/unchanged"), title = GigTitle("Unchanged Gig"), date = LocalDate.of(2026, 8, 9), imageUrl = "", description = "")
        val soldOutBefore = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/changed"), title = GigTitle("Changed Gig"), date = LocalDate.of(2026, 8, 10), imageUrl = "", description = "")
        val soldOutNow = soldOutBefore.copy(title = GigTitle("Changed Gig - SOLD OUT"))
        val existingEntries: List<LogEntry> = listOf(
            GigObserved(unchangedGig, Instant.parse("2026-07-01T00:00:00Z")),
            GigObserved(soldOutBefore, Instant.parse("2026-07-01T00:00:00Z")),
        )

        val newOrChanged = gigsLog(existingEntries).newOrChangedGigs(listOf(unseen, unchangedGig, soldOutNow))

        expectThat(newOrChanged).containsExactlyInAnyOrder(unseen, soldOutNow)
    }

    @Test
    fun `treats a gig as changed again if it reverts to a state seen earlier than its latest observation`() {
        val original = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/reverting"), title = GigTitle("Reverting Gig"), date = LocalDate.of(2026, 8, 8), imageUrl = "", description = "")
        val soldOut = original.copy(title = GigTitle("Reverting Gig - SOLD OUT"))
        val existingEntries: List<LogEntry> = listOf(
            GigObserved(original, Instant.parse("2026-07-01T00:00:00Z")),
            GigObserved(soldOut, Instant.parse("2026-07-10T00:00:00Z")),
        )

        val newOrChanged = gigsLog(existingEntries).newOrChangedGigs(listOf(original))

        expectThat(newOrChanged).containsExactly(original)
    }

    @Test
    fun `derives last-scraped time per venue from the latest observation, ignoring venues never observed`() {
        val gigA1 = Gig(id = GigId(VenueId("Venue A"), "https://example.com/gigs/a1"), title = GigTitle("Gig A1"), date = LocalDate.of(2026, 8, 8), imageUrl = "", description = "")
        val gigA2 = Gig(id = GigId(VenueId("Venue A"), "https://example.com/gigs/a2"), title = GigTitle("Gig A2"), date = LocalDate.of(2026, 8, 9), imageUrl = "", description = "")
        val gigB = Gig(id = GigId(VenueId("Venue B"), "https://example.com/gigs/b"), title = GigTitle("Gig B"), date = LocalDate.of(2026, 8, 8), imageUrl = "", description = "")
        val events: List<LogEntry> = listOf(
            GigObserved(gigA1, Instant.parse("2026-07-01T00:00:00Z")),
            GigObserved(gigA2, Instant.parse("2026-07-10T00:00:00Z")),
            GigObserved(gigB, Instant.parse("2026-07-05T00:00:00Z")),
        )

        val lastScrapedAt = gigsLog(events).lastScrapedAt()

        expectThat(lastScrapedAt).isEqualTo(
            mapOf(VenueId("Venue A") to Instant.parse("2026-07-10T00:00:00Z"), VenueId("Venue B") to Instant.parse("2026-07-05T00:00:00Z")),
        )
        expectThat(lastScrapedAt[VenueId("Venue Never Scraped")]).isEqualTo(null)
    }

    @Test
    fun `detects an already-ingested poster by its gigs' shared source-url prefix`() {
        val gig = Gig(id = GigId(VenueId("Some Venue"), "https://example.com/post/1#gig-doom-night-2026-08-14"), title = GigTitle("Doom Night"), date = LocalDate.of(2026, 8, 14), imageUrl = "", description = "")
        val events: List<LogEntry> = listOf(GigObserved(gig, Instant.parse("2026-07-01T00:00:00Z")))

        val log = gigsLog(events)
        expectThat(log.alreadyIngested("https://example.com/post/1")).isEqualTo(true)
        expectThat(log.alreadyIngested("https://example.com/post/2")).isEqualTo(false)
    }

    @Test
    fun `knows whether the page has been rendered for a given date`() {
        val entries: List<LogEntry> = listOf(
            GigsRendered("2026-08-10T09-00-00Z.html", gigCount = 3, logicalDate = LocalDate.of(2026, 8, 10), recordedAt = Instant.parse("2026-08-10T09:00:00Z")),
            // a backdated render, made later than the one above but for an earlier page
            GigsRendered("2026-08-11T09-00-00Z.html", gigCount = 9, logicalDate = LocalDate.of(2026, 1, 1), recordedAt = Instant.parse("2026-08-11T09:00:00Z")),
        )

        val log = gigsLog(entries)
        expectThat(log.alreadyRenderedFor(LocalDate.of(2026, 8, 10))).isEqualTo(true)
        // the newest render was for 1 Jan, so it must not count as having rendered the 11th
        expectThat(log.alreadyRenderedFor(LocalDate.of(2026, 8, 11))).isEqualTo(false)
        expectThat(gigsLog(emptyList()).alreadyRenderedFor(LocalDate.of(2026, 8, 10))).isEqualTo(false)
    }

    // it's the one entry with no gig behind it, so every projection has to step over it rather
    // than assume each entry names a gig
    @Test
    fun `ignores a render entry when projecting gigs`() {
        val gig = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/metal"), title = GigTitle("Metal"), date = LocalDate.of(2026, 8, 9), imageUrl = "", description = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(
            GigObserved(gig, recordedAt),
            GigClassified(gig.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigsRendered("2026-07-01T00-00-00Z.html", gigCount = 1, logicalDate = LocalDate.of(2026, 8, 1), recordedAt = recordedAt),
        )

        val log = gigsLog(events)
        expectThat(log.currentGigs()).isEqualTo(listOf(gig))
        expectThat(log.metalGigs()).isEqualTo(listOf(gig))
        expectThat(log.alreadyClassified()).isEqualTo(setOf(gig.id))
        expectThat(log.lastScrapedAt()).isEqualTo(mapOf(VenueId("Test Venue") to recordedAt))
        expectThat(log.alreadyIngested("https://example.com/post/1")).isEqualTo(false)
    }

    @Test
    fun `projects only gigs classified Metal, excluding never-classified ones`() {
        val neverClassified = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/never-classified"), title = GigTitle("Never Classified"), date = LocalDate.of(2026, 8, 8), imageUrl = "", description = "")
        val metal = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/metal"), title = GigTitle("Metal"), date = LocalDate.of(2026, 8, 9), imageUrl = "", description = "")
        val other = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/other"), title = GigTitle("Other"), date = LocalDate.of(2026, 8, 10), imageUrl = "", description = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(
            GigObserved(neverClassified, recordedAt),
            GigObserved(metal, recordedAt),
            GigClassified(metal.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigObserved(other, recordedAt),
            GigClassified(other.id, recordedAt, Genre.Other, ClassificationSource.LLM),
        )

        expectThat(gigsLog(events).metalGigs()).isEqualTo(listOf(metal))
    }

    @Test
    fun `computes classification status per gig, using the latest classification`() {
        val classified = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/classified"), title = GigTitle("Classified"), date = LocalDate.of(2026, 8, 9), imageUrl = "", description = "")
        val reclassified = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/reclassified"), title = GigTitle("Reclassified"), date = LocalDate.of(2026, 8, 10), imageUrl = "", description = "")
        val neverClassified = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/never-classified"), title = GigTitle("Never Classified"), date = LocalDate.of(2026, 8, 11), imageUrl = "", description = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(
            GigObserved(classified, recordedAt),
            GigClassified(classified.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigObserved(reclassified, recordedAt),
            GigClassified(reclassified.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigClassified(reclassified.id, Instant.parse("2026-07-15T00:00:00Z"), Genre.Other, ClassificationSource.LLM),
            GigObserved(neverClassified, recordedAt),
        )

        val statusByGig = gigsLog(events).classificationStatus()

        expectThat(statusByGig[classified.id]).isEqualTo(ClassificationStatus.Classified(Genre.Metal))
        expectThat(statusByGig[reclassified.id]).isEqualTo(ClassificationStatus.Classified(Genre.Other))
        expectThat(statusByGig[neverClassified.id]).isEqualTo(null)
    }

    @Test
    fun `a user override always beats the classifier's verdict`() {
        val overriddenToMetal = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/overridden-to-metal"), title = GigTitle("Overridden To Metal"), date = LocalDate.of(2026, 8, 8), imageUrl = "", description = "")
        val overriddenToOther = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/overridden-to-other"), title = GigTitle("Overridden To Other"), date = LocalDate.of(2026, 8, 9), imageUrl = "", description = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(
            GigObserved(overriddenToMetal, recordedAt),
            GigClassified(overriddenToMetal.id, recordedAt, Genre.Other, ClassificationSource.LLM),
            GigClassified(overriddenToMetal.id, recordedAt, Genre.Metal, ClassificationSource.User),
            GigObserved(overriddenToOther, recordedAt),
            GigClassified(overriddenToOther.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigClassified(overriddenToOther.id, recordedAt, Genre.Other, ClassificationSource.User),
        )

        val log = gigsLog(events)
        expectThat(log.metalGigs()).isEqualTo(listOf(overriddenToMetal))
        expectThat(log.classificationStatus()[overriddenToOther.id]).isEqualTo(ClassificationStatus.Classified(Genre.Other))
    }

    @Test
    fun `treats any classification as already-classified, whoever made it`() {
        val classified = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/classified"), title = GigTitle("Classified"), date = LocalDate.of(2026, 8, 8), imageUrl = "", description = "")
        val userOverridden = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/user-overridden"), title = GigTitle("User Overridden"), date = LocalDate.of(2026, 8, 10), imageUrl = "", description = "")
        val neverClassified = Gig(id = GigId(VenueId("Test Venue"), "https://example.com/gigs/never-classified"), title = GigTitle("Never Classified"), date = LocalDate.of(2026, 8, 11), imageUrl = "", description = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(
            GigObserved(classified, recordedAt),
            GigClassified(classified.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigObserved(userOverridden, recordedAt),
            GigClassified(userOverridden.id, recordedAt, Genre.Metal, ClassificationSource.User),
            GigObserved(neverClassified, recordedAt),
        )

        expectThat(gigsLog(events).alreadyClassified()).containsExactlyInAnyOrder(classified.id, userOverridden.id)
    }
}
