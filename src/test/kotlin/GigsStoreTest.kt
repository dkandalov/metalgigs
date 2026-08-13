import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test

class GigsStoreTest {

    @Test
    fun `appends and reads back gig log entries of different kinds`() {
        val gig = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/test-gig"), title = "Test Gig", date = LocalDate.of(2026, 8, 8), imageUrl = "https://example.com/images/test-gig.jpg")
        val recordedAt = Instant.parse("2026-08-01T12:00:00Z")
        val entries: List<LogEntry> = listOf(
            GigObserved(gig, recordedAt),
            GigClassified(gig.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigsRendered("2026-08-01T12-00-00Z.html", gigCount = 1, logicalDate = LocalDate.of(2026, 8, 1), recordedAt = recordedAt),
        )
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }

        appendLogEntries(file, entries)

        expectThat(readLogEntries(file)).isEqualTo(entries)
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

        appendLogEntries(file, listOf(rendered))

        expectThat(file.readText().trim()).isEqualTo(
            """{"_type": "rendered", "file": "2026-08-01T12-00-00Z.html", "gigCount": 42, "logicalDate": "2026-08-01", "recordedAt": "2026-08-01T12:00:00Z"}""",
        )
    }

    @Test
    fun `round-trips a classification recording which model judged it and whether it saw the poster`() {
        val recordedAt = Instant.parse("2026-08-01T12:00:00Z")
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        val classified = GigClassified(
            id = GigId(Venue("Test Venue"), "https://example.com/gigs/test-gig"),
            recordedAt = recordedAt,
            genre = Genre.Metal,
            source = ClassificationSource.LLM,
            llmModel = "claude-haiku-4-5-20251001",
            useVision = false,
        )

        appendLogEntries(file, listOf(classified))

        expectThat(readLogEntries(file)).isEqualTo(listOf(classified))
    }

    // verbatim from the real log, from before llmModel and useVision were added - no keys for
    // either, not null-valued keys, so this is what an absent JFieldMaybe actually has to handle
    @Test
    fun `reads back a classification written before llmModel and useVision existed`() {
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        file.writeText(
            """{"_type": "classified", "venue": "Signature Brew Blackhorse Road", "url": "https://tixr.com/e/187182", "recordedAt": "2026-08-11T21:20:43.785398Z", "genre": "Other", "source": "LLM"}""" + "\n",
        )

        expectThat(readLogEntries(file)).isEqualTo(
            listOf(
                GigClassified(
                    id = GigId(Venue("Signature Brew Blackhorse Road"), "https://tixr.com/e/187182"),
                    recordedAt = Instant.parse("2026-08-11T21:20:43.785398Z"),
                    genre = Genre.Other,
                    source = ClassificationSource.LLM,
                ),
            ),
        )
    }

    @Test
    fun `reads back an observation written before the description existed, and one with it`() {
        val gig = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/test-gig"), title = "Test Gig", date = LocalDate.of(2026, 8, 8), imageUrl = "")
        val recordedAt = Instant.parse("2026-08-01T12:00:00Z")
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        // No description key at all, which no line in the log currently has - this pins the optional
        // read, so a hand-edited or truncated line degrades to "" instead of failing the whole read.
        file.writeText("""{"_type": "observed", "gig": {"title": "Test Gig", "venue": "Test Venue", "date": "2026-08-08", "url": "https://example.com/gigs/test-gig", "imageUrl": ""}, "recordedAt": "2026-08-01T12:00:00Z"}""" + "\n")

        appendLogEntries(file, listOf(GigObserved(gig.copy(description = "Doom metal night"), recordedAt.plusSeconds(60))))

        expectThat(readLogEntries(file)).isEqualTo(
            listOf(
                GigObserved(gig, recordedAt),
                GigObserved(gig.copy(description = "Doom metal night"), recordedAt.plusSeconds(60)),
            ),
        )
    }

    @Test
    fun `a gig whose description was never captured is not treated as changed`() {
        val gig = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/never"), title = "Never", date = LocalDate.of(2026, 8, 9), imageUrl = "")
        val existing: List<LogEntry> = listOf(GigObserved(gig, Instant.parse("2026-07-01T00:00:00Z")))

        expectThat(newOrChangedGigs(existing, listOf(gig))).isEqualTo(emptyList())
    }

    @Test
    fun `the description changing does not make a gig count as changed`() {
        val gig = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/some-gig"), title = "Some Gig", date = LocalDate.of(2026, 8, 8), imageUrl = "")
        val existing: List<LogEntry> = listOf(GigObserved(gig.copy(description = "3 tickets left"), Instant.parse("2026-07-01T00:00:00Z")))

        // the same gig as the listing gives it - a counter ticking over on its own page, or that
        // page failing to render, must not look like the gig itself changed
        expectThat(newOrChangedGigs(existing, listOf(gig.copy(description = "2 tickets left")))).isEqualTo(emptyList())
        expectThat(newOrChangedGigs(existing, listOf(gig.copy(description = "")))).isEqualTo(emptyList())
        expectThat(newOrChangedGigs(existing, listOf(gig.copy(title = "Some Gig - SOLD OUT")))).containsExactly(gig.copy(title = "Some Gig - SOLD OUT"))
    }

    @Test
    fun `projects the latest observation per gig, ignoring classification entries`() {
        val firstSeen = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/some-gig"), title = "Some Gig", date = LocalDate.of(2026, 8, 8), imageUrl = "https://example.com/images/some-gig.jpg")
        val soldOut = firstSeen.copy(title = "Some Gig - SOLD OUT")
        val events: List<LogEntry> = listOf(
            GigObserved(firstSeen, Instant.parse("2026-07-01T00:00:00Z")),
            GigClassified(firstSeen.id, Instant.parse("2026-07-10T00:00:00Z"), Genre.Metal, ClassificationSource.LLM),
            GigObserved(soldOut, Instant.parse("2026-07-15T00:00:00Z")),
        )

        expectThat(projectCurrentGigs(events)).isEqualTo(listOf(soldOut))
    }

    @Test
    fun `keeps separate gigs from different venues distinct`() {
        val gigA = Gig(id = GigId(Venue("Venue A"), "https://example.com/gigs/same-slug"), title = "Gig A", date = LocalDate.of(2026, 8, 8), imageUrl = "https://example.com/images/gig-a.jpg")
        val gigB = Gig(id = GigId(Venue("Venue B"), "https://example.com/gigs/same-slug"), title = "Gig B", date = LocalDate.of(2026, 8, 8), imageUrl = "https://example.com/images/gig-b.jpg")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events = listOf(GigObserved(gigA, recordedAt), GigObserved(gigB, recordedAt))

        expectThat(projectCurrentGigs(events)).containsExactlyInAnyOrder(gigA, gigB)
    }

    @Test
    fun `treats a gig as new or changed if it's unseen or differs from its latest observation`() {
        val unseen = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/unseen"), title = "Unseen Gig", date = LocalDate.of(2026, 8, 8), imageUrl = "")
        val unchangedGig = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/unchanged"), title = "Unchanged Gig", date = LocalDate.of(2026, 8, 9), imageUrl = "")
        val soldOutBefore = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/changed"), title = "Changed Gig", date = LocalDate.of(2026, 8, 10), imageUrl = "")
        val soldOutNow = soldOutBefore.copy(title = "Changed Gig - SOLD OUT")
        val existingEntries: List<LogEntry> = listOf(
            GigObserved(unchangedGig, Instant.parse("2026-07-01T00:00:00Z")),
            GigObserved(soldOutBefore, Instant.parse("2026-07-01T00:00:00Z")),
        )

        val newOrChanged = newOrChangedGigs(existingEntries, scrapedGigs = listOf(unseen, unchangedGig, soldOutNow))

        expectThat(newOrChanged).containsExactlyInAnyOrder(unseen, soldOutNow)
    }

    @Test
    fun `treats a gig as changed again if it reverts to a state seen earlier than its latest observation`() {
        val original = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/reverting"), title = "Reverting Gig", date = LocalDate.of(2026, 8, 8), imageUrl = "")
        val soldOut = original.copy(title = "Reverting Gig - SOLD OUT")
        val existingEntries: List<LogEntry> = listOf(
            GigObserved(original, Instant.parse("2026-07-01T00:00:00Z")),
            GigObserved(soldOut, Instant.parse("2026-07-10T00:00:00Z")),
        )

        val newOrChanged = newOrChangedGigs(existingEntries, scrapedGigs = listOf(original))

        expectThat(newOrChanged).containsExactly(original)
    }

    @Test
    fun `derives last-scraped time per venue from the latest observation, ignoring venues never observed`() {
        val gigA1 = Gig(id = GigId(Venue("Venue A"), "https://example.com/gigs/a1"), title = "Gig A1", date = LocalDate.of(2026, 8, 8), imageUrl = "")
        val gigA2 = Gig(id = GigId(Venue("Venue A"), "https://example.com/gigs/a2"), title = "Gig A2", date = LocalDate.of(2026, 8, 9), imageUrl = "")
        val gigB = Gig(id = GigId(Venue("Venue B"), "https://example.com/gigs/b"), title = "Gig B", date = LocalDate.of(2026, 8, 8), imageUrl = "")
        val events: List<LogEntry> = listOf(
            GigObserved(gigA1, Instant.parse("2026-07-01T00:00:00Z")),
            GigObserved(gigA2, Instant.parse("2026-07-10T00:00:00Z")),
            GigObserved(gigB, Instant.parse("2026-07-05T00:00:00Z")),
        )

        val lastScrapedAt = lastScrapedAt(events)

        expectThat(lastScrapedAt).isEqualTo(
            mapOf(Venue("Venue A") to Instant.parse("2026-07-10T00:00:00Z"), Venue("Venue B") to Instant.parse("2026-07-05T00:00:00Z")),
        )
        expectThat(lastScrapedAt[Venue("Venue Never Scraped")]).isEqualTo(null)
    }

    @Test
    fun `detects an already-ingested poster by its gigs' shared source-url prefix`() {
        val gig = Gig(id = GigId(Venue("Some Venue"), "https://example.com/post/1#gig-doom-night-2026-08-14"), title = "Doom Night", date = LocalDate.of(2026, 8, 14), imageUrl = "")
        val events: List<LogEntry> = listOf(GigObserved(gig, Instant.parse("2026-07-01T00:00:00Z")))

        expectThat(alreadyIngested(events, "https://example.com/post/1")).isEqualTo(true)
        expectThat(alreadyIngested(events, "https://example.com/post/2")).isEqualTo(false)
    }

    @Test
    fun `knows whether the page has been rendered for a given date`() {
        val entries: List<LogEntry> = listOf(
            GigsRendered("2026-08-10T09-00-00Z.html", gigCount = 3, logicalDate = LocalDate.of(2026, 8, 10), recordedAt = Instant.parse("2026-08-10T09:00:00Z")),
            // a backdated render, made later than the one above but for an earlier page
            GigsRendered("2026-08-11T09-00-00Z.html", gigCount = 9, logicalDate = LocalDate.of(2026, 1, 1), recordedAt = Instant.parse("2026-08-11T09:00:00Z")),
        )

        expectThat(alreadyRenderedFor(entries, LocalDate.of(2026, 8, 10))).isEqualTo(true)
        // the newest render was for 1 Jan, so it must not count as having rendered the 11th
        expectThat(alreadyRenderedFor(entries, LocalDate.of(2026, 8, 11))).isEqualTo(false)
        expectThat(alreadyRenderedFor(emptyList(), LocalDate.of(2026, 8, 10))).isEqualTo(false)
    }

    // it's the one entry with no gig behind it, so every projection has to step over it rather
    // than assume each entry names a gig
    @Test
    fun `ignores a render entry when projecting gigs`() {
        val gig = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/metal"), title = "Metal", date = LocalDate.of(2026, 8, 9), imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(
            GigObserved(gig, recordedAt),
            GigClassified(gig.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigsRendered("2026-07-01T00-00-00Z.html", gigCount = 1, logicalDate = LocalDate.of(2026, 8, 1), recordedAt = recordedAt),
        )

        expectThat(projectCurrentGigs(events)).isEqualTo(listOf(gig))
        expectThat(projectMetalGigs(events)).isEqualTo(listOf(gig))
        expectThat(alreadyClassified(events)).isEqualTo(setOf(gig.id))
        expectThat(lastScrapedAt(events)).isEqualTo(mapOf(Venue("Test Venue") to recordedAt))
        expectThat(alreadyIngested(events, "https://example.com/post/1")).isEqualTo(false)
    }

    @Test
    fun `projects only gigs classified Metal, excluding never-classified ones`() {
        val neverClassified = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/never-classified"), title = "Never Classified", date = LocalDate.of(2026, 8, 8), imageUrl = "")
        val metal = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/metal"), title = "Metal", date = LocalDate.of(2026, 8, 9), imageUrl = "")
        val other = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/other"), title = "Other", date = LocalDate.of(2026, 8, 10), imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(
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
        val classified = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/classified"), title = "Classified", date = LocalDate.of(2026, 8, 9), imageUrl = "")
        val reclassified = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/reclassified"), title = "Reclassified", date = LocalDate.of(2026, 8, 10), imageUrl = "")
        val neverClassified = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/never-classified"), title = "Never Classified", date = LocalDate.of(2026, 8, 11), imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(
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
        val overriddenToMetal = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/overridden-to-metal"), title = "Overridden To Metal", date = LocalDate.of(2026, 8, 8), imageUrl = "")
        val overriddenToOther = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/overridden-to-other"), title = "Overridden To Other", date = LocalDate.of(2026, 8, 9), imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(
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
        val classified = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/classified"), title = "Classified", date = LocalDate.of(2026, 8, 8), imageUrl = "")
        val userOverridden = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/user-overridden"), title = "User Overridden", date = LocalDate.of(2026, 8, 10), imageUrl = "")
        val neverClassified = Gig(id = GigId(Venue("Test Venue"), "https://example.com/gigs/never-classified"), title = "Never Classified", date = LocalDate.of(2026, 8, 11), imageUrl = "")
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(
            GigObserved(classified, recordedAt),
            GigClassified(classified.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigObserved(userOverridden, recordedAt),
            GigClassified(userOverridden.id, recordedAt, Genre.Metal, ClassificationSource.User),
            GigObserved(neverClassified, recordedAt),
        )

        expectThat(alreadyClassified(events)).containsExactlyInAnyOrder(classified.id, userOverridden.id)
    }
}
