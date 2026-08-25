package metalgigs

import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GigsStoreTest {

    private fun gigsLog(entries: List<LogEntry>): GigsLog {
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        return GigsLog(file).apply { append(entries) }
    }

    @Test
    fun `appends and reads back gig log entries of different kinds`() {
        val gig = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/test-gig")), GigTitle("Test Gig"), GigDate(2026, 8, 8), PosterUrl("https://example.com/images/test-gig.jpg"), GigDescription(""))
        val recordedAt = Instant.parse("2026-08-01T12:00:00Z")
        val entries: List<LogEntry> = listOf(
            GigObserved(gig, recordedAt),
            GigClassified(gig.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigsRendered("2026-08-01T12-00-00Z.html", 1, LocalDate.of(2026, 8, 1), recordedAt),
        )
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }

        val log = GigsLog(file).apply { append(entries) }

        expectThat(GigsLog(file).entries).isEqualTo(log.entries)
    }

    @Test
    fun `numbers appended entries in order, continuing from what the log already holds`() {
        val gig = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/test-gig")), GigTitle("Test Gig"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val recordedAt = Instant.parse("2026-08-01T12:00:00Z")
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }

        GigsLog(file).append(listOf(GigObserved(gig, recordedAt), GigClassified(gig.id, recordedAt, Genre.Metal, ClassificationSource.LLM)))
        GigsLog(file).append(listOf(GigObserved(gig.copy(title = GigTitle("Test Gig - SOLD OUT")), recordedAt)))

        expectThat(GigsLog(file).entries.map { it.seq }).isEqualTo(listOf(0L, 1L, 2L))
    }

    // compactLog appends already-logged entries to a fresh file, and a seq that changed on the way
    // wouldn't identify anything - the gaps are what say entries were dropped
    @Test
    fun `keeps the seq of an entry that already has one`() {
        val gig = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/test-gig")), GigTitle("Test Gig"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val recordedAt = Instant.parse("2026-08-01T12:00:00Z")
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }

        GigsLog(file).append(listOf(GigObserved(gig, recordedAt, seq = 7), GigObserved(gig, recordedAt, seq = 9)))

        expectThat(GigsLog(file).entries.map { it.seq }).isEqualTo(listOf(7L, 9L))
    }

    @Test
    fun `refuses an entry whose seq would leave the log out of order`() {
        val gig = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/test-gig")), GigTitle("Test Gig"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val recordedAt = Instant.parse("2026-08-01T12:00:00Z")
        val log = gigsLog(listOf(GigObserved(gig, recordedAt, seq = 5)))

        assertFailsWith<IllegalArgumentException> { log.append(listOf(GigObserved(gig, recordedAt, seq = 5))) }
    }

    @Test
    fun `writes a render entry with its logical date as a plain ISO date`() {
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        val rendered = GigsRendered(
            "2026-08-01T12-00-00Z.html",
            42,
            LocalDate.of(2026, 8, 1),
            Instant.parse("2026-08-01T12:00:00Z"),
        )

        GigsLog(file).append(listOf(rendered))

        expectThat(file.readText().trim()).isEqualTo(
            """{"_type": "rendered", "seq": 0, "file": "2026-08-01T12-00-00Z.html", "gigCount": 42, "logicalDate": "2026-08-01", "recordedAt": "2026-08-01T12:00:00Z"}""",
        )
    }

    @Test
    fun `round-trips a classification recording the model, the poster, and what it billed`() {
        val recordedAt = Instant.parse("2026-08-01T12:00:00Z")
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        val classified = GigClassified(
            id = GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/test-gig")),
            recordedAt = recordedAt,
            genre = Genre.Metal,
            source = ClassificationSource.LLM,
            llmModel = "claude-haiku-4-5-20251001",
            useVision = false,
            inputTokens = 1234,
            outputTokens = 3,
            seq = 0,
        )

        GigsLog(file).append(listOf(classified))

        expectThat(GigsLog(file).entries).isEqualTo(listOf(classified))
    }

    // a real log line from before llmModel and useVision were added, renumbered to seq 0 - no keys
    // for either, not null-valued keys, so this is what an absent JFieldMaybe actually has to handle
    @Test
    fun `reads back a classification written before llmModel and useVision existed`() {
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }
        file.writeText(
            """{"_type": "classified", "seq": 0, "venue": "Signature Brew Blackhorse Road", "url": "https://tixr.com/e/187182", "recordedAt": "2026-08-11T21:20:43.785398Z", "genre": "Other", "source": "LLM"}""" + "\n",
        )

        expectThat(GigsLog(file).entries).isEqualTo(
            listOf(
                GigClassified(
                    GigId(VenueId("Signature Brew Blackhorse Road"), GigUrl("https://tixr.com/e/187182")),
                    Instant.parse("2026-08-11T21:20:43.785398Z"),
                    Genre.Other,
                    ClassificationSource.LLM,
                    seq = 0,
                ),
            ),
        )
    }

    @Test
    fun `reads back an observation whose page said nothing, and one with text`() {
        val gig = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/test-gig")), GigTitle("Test Gig"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val recordedAt = Instant.parse("2026-08-01T12:00:00Z")
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }

        GigsLog(file).append(listOf(GigObserved(gig, recordedAt), GigObserved(gig.copy(description = GigDescription("Doom metal night")), recordedAt.plusSeconds(60))))

        expectThat(GigsLog(file).entries).isEqualTo(
            listOf(
                GigObserved(gig, recordedAt, seq = 0),
                GigObserved(gig.copy(description = GigDescription("Doom metal night")), recordedAt.plusSeconds(60), seq = 1),
            ),
        )
    }

    // The reason seq exists: a scrape stamps every observation it logs with one Instant, and a
    // classification run does the same, so recordedAt alone can't say which entry came last.
    @Test
    fun `takes the later of two observations recorded at the same instant`() {
        val gig = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/some-gig")), GigTitle("Some Gig"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val soldOut = gig.copy(title = GigTitle("Some Gig - SOLD OUT"))
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(GigObserved(gig, recordedAt), GigObserved(soldOut, recordedAt))

        expectThat(gigsLog(events).currentGigs()).isEqualTo(listOf(soldOut))
    }

    @Test
    fun `takes the later of two classifications recorded at the same instant`() {
        val gig = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/some-gig")), GigTitle("Some Gig"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(
            GigObserved(gig, recordedAt),
            GigClassified(gig.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigClassified(gig.id, recordedAt, Genre.Other, ClassificationSource.LLM),
        )

        expectThat(gigsLog(events).classificationStatus()[gig.id]).isEqualTo(ClassificationStatus.Classified(Genre.Other))
    }

    @Test
    fun `a changed description makes a gig count as changed`() {
        val gig = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/some-gig")), GigTitle("Some Gig"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription("3 tickets left"))
        val existing: List<LogEntry> = listOf(GigObserved(gig, Instant.parse("2026-07-01T00:00:00Z")))
        val log = gigsLog(existing)

        val changed = gig.copy(description = GigDescription("2 tickets left"))
        expectThat(log.newOrChangedGigs(listOf(changed))).containsExactly(changed)
        expectThat(log.newOrChangedGigs(listOf(gig))).isEqualTo(emptyList())
    }

    @Test
    fun `projects the latest observation per gig, ignoring classification entries`() {
        val firstSeen = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/some-gig")), GigTitle("Some Gig"), GigDate(2026, 8, 8), PosterUrl("https://example.com/images/some-gig.jpg"), GigDescription(""))
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
        val gigA = Gig(GigId(VenueId("Venue A"), GigUrl("https://example.com/gigs/same-slug")), GigTitle("Gig A"), GigDate(2026, 8, 8), PosterUrl("https://example.com/images/gig-a.jpg"), GigDescription(""))
        val gigB = Gig(GigId(VenueId("Venue B"), GigUrl("https://example.com/gigs/same-slug")), GigTitle("Gig B"), GigDate(2026, 8, 8), PosterUrl("https://example.com/images/gig-b.jpg"), GigDescription(""))
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events = listOf(GigObserved(gigA, recordedAt), GigObserved(gigB, recordedAt))

        expectThat(gigsLog(events).currentGigs()).containsExactlyInAnyOrder(gigA, gigB)
    }

    @Test
    fun `treats a gig as new or changed if it's unseen or differs from its latest observation`() {
        val unseen = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/unseen")), GigTitle("Unseen Gig"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val unchangedGig = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/unchanged")), GigTitle("Unchanged Gig"), GigDate(2026, 8, 9), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val soldOutBefore = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/changed")), GigTitle("Changed Gig"), GigDate(2026, 8, 10), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
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
        val original = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/reverting")), GigTitle("Reverting Gig"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
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
        val gigA1 = Gig(GigId(VenueId("Venue A"), GigUrl("https://example.com/gigs/a1")), GigTitle("Gig A1"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val gigA2 = Gig(GigId(VenueId("Venue A"), GigUrl("https://example.com/gigs/a2")), GigTitle("Gig A2"), GigDate(2026, 8, 9), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val gigB = Gig(GigId(VenueId("Venue B"), GigUrl("https://example.com/gigs/b")), GigTitle("Gig B"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
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

    // the trap: a venue editing a title re-logs the gig, so the latest observation dates it to the edit
    @Test
    fun `dates a gig from its earliest observation, however often its listing changed after`() {
        val gig = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/a")), GigTitle("Gig A"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val events: List<LogEntry> = listOf(
            GigObserved(gig, Instant.parse("2026-07-01T00:00:00Z")),
            GigObserved(gig.copy(title = GigTitle("Gig A - SOLD OUT")), Instant.parse("2026-07-10T00:00:00Z")),
        )

        expectThat(gigsLog(events).firstSeenAt()).isEqualTo(mapOf(gig.id to Instant.parse("2026-07-01T00:00:00Z")))
    }

    @Test
    fun `dates a relisted gig from the gig it replaced, twice over if it moved twice`() {
        val first = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/a")), GigTitle("Gig A"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val second = first.copy(id = GigId(first.id.venueId, GigUrl("https://example.com/gigs/a-plus-support")))
        val third = first.copy(id = GigId(first.id.venueId, GigUrl("https://example.com/gigs/a-plus-two-supports")))
        val events: List<LogEntry> = listOf(
            GigObserved(first, Instant.parse("2026-07-01T00:00:00Z")),
            GigObserved(second, Instant.parse("2026-07-05T00:00:00Z")),
            GigReplaced(first.id, second.id, Instant.parse("2026-07-05T00:00:00Z")),
            GigObserved(third, Instant.parse("2026-07-09T00:00:00Z")),
            GigReplaced(second.id, third.id, Instant.parse("2026-07-09T00:00:00Z")),
        )

        expectThat(gigsLog(events).firstSeenAt()[third.id]).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"))
    }

    @Test
    fun `knows whether the page has been rendered for a given date`() {
        val entries: List<LogEntry> = listOf(
            GigsRendered("2026-08-10T09-00-00Z.html", 3, LocalDate.of(2026, 8, 10), Instant.parse("2026-08-10T09:00:00Z")),
            // a backdated render, made later than the one above but for an earlier page
            GigsRendered("2026-08-11T09-00-00Z.html", 9, LocalDate.of(2026, 1, 1), Instant.parse("2026-08-11T09:00:00Z")),
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
        val gig = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/metal")), GigTitle("Metal"), GigDate(2026, 8, 9), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(
            GigObserved(gig, recordedAt),
            GigClassified(gig.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigsRendered("2026-07-01T00-00-00Z.html", 1, LocalDate.of(2026, 8, 1), recordedAt),
        )

        val log = gigsLog(events)
        expectThat(log.currentGigs()).isEqualTo(listOf(gig))
        expectThat(log.metalGigs()).isEqualTo(listOf(gig))
        expectThat(log.alreadyClassified()).isEqualTo(setOf(gig.id))
        expectThat(log.lastScrapedAt()).isEqualTo(mapOf(VenueId("Test Venue") to recordedAt))
    }

    @Test
    fun `projects only gigs classified Metal, excluding never-classified ones`() {
        val neverClassified = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/never-classified")), GigTitle("Never Classified"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val metal = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/metal")), GigTitle("Metal"), GigDate(2026, 8, 9), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val other = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/other")), GigTitle("Other"), GigDate(2026, 8, 10), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
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
        val classified = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/classified")), GigTitle("Classified"), GigDate(2026, 8, 9), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val reclassified = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/reclassified")), GigTitle("Reclassified"), GigDate(2026, 8, 10), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val neverClassified = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/never-classified")), GigTitle("Never Classified"), GigDate(2026, 8, 11), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
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
        val overriddenToMetal = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/overridden-to-metal")), GigTitle("Overridden To Metal"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val overriddenToOther = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/overridden-to-other")), GigTitle("Overridden To Other"), GigDate(2026, 8, 9), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
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
        val classified = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/classified")), GigTitle("Classified"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val userOverridden = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/user-overridden")), GigTitle("User Overridden"), GigDate(2026, 8, 10), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val neverClassified = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/never-classified")), GigTitle("Never Classified"), GigDate(2026, 8, 11), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
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

    // What a forced classify run skips, against what an ordinary one does: only the gig whose genre
    // a user asserted, since re-asking about that one changes nothing it would then read back.
    @Test
    fun `counts only a user's overrides as settled against the classifier`() {
        val classified = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/classified")), GigTitle("Classified"), GigDate(2026, 8, 8), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val userOverridden = Gig(GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/user-overridden")), GigTitle("User Overridden"), GigDate(2026, 8, 10), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))
        val recordedAt = Instant.parse("2026-07-01T00:00:00Z")
        val events: List<LogEntry> = listOf(
            GigObserved(classified, recordedAt),
            GigClassified(classified.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
            GigObserved(userOverridden, recordedAt),
            // the classifier having since had its own say doesn't unsettle the override
            GigClassified(userOverridden.id, recordedAt, Genre.Metal, ClassificationSource.User),
            GigClassified(userOverridden.id, recordedAt, Genre.Other, ClassificationSource.LLM),
        )

        expectThat(gigsLog(events).overriddenByUser()).containsExactlyInAnyOrder(userOverridden.id)
    }
    private val venue = VenueId("Signature Brew Haggerston")
    private val recordedAt = Instant.parse("2026-08-21T12:00:00Z")

    private fun gigAt(url: String, title: String = "LOLA (AUS)") =
        Gig(GigId(venue, GigUrl(url)), GigTitle(title), GigDate(2026, 9, 10), PosterUrl("https://example.com/poster.jpg"), GigDescription(""))

    @Test
    fun `reads back a replacement, both urls under the venue that listed them`() {
        val moved = gigAt("https://example.com/lola-aus")
        val listed = gigAt("https://example.com/eo6xmw-lola-aus-lucky-hit")
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }

        val log = GigsLog(file).apply { append(listOf(GigReplaced(moved.id, listed.id, recordedAt))) }

        expectThat(GigsLog(file).entries).isEqualTo(log.entries)
    }

    @Test
    fun `leaves a gig its venue has relisted out of the current gigs`() {
        val moved = gigAt("https://example.com/lola-aus")
        val listed = gigAt("https://example.com/eo6xmw-lola-aus-lucky-hit")
        val log = gigsLog(listOf(GigObserved(moved, recordedAt), GigObserved(listed, recordedAt), GigReplaced(moved.id, listed.id, recordedAt)))

        expectThat(log.currentGigs()).isEqualTo(listOf(listed))
    }

    // a listing edited twice leaves two urls behind, and each of them is some replacement's own
    // `replaced`, so nothing has to walk the chain to drop them
    @Test
    fun `leaves every url a twice-relisted gig has left behind out of the current gigs`() {
        val first = gigAt("https://example.com/lola-aus")
        val second = gigAt("https://example.com/lola-aus-lucky-hit")
        val third = gigAt("https://example.com/lola-aus-lucky-hit-and-support")
        val log = gigsLog(
            listOf(
                GigObserved(first, recordedAt), GigObserved(second, recordedAt), GigObserved(third, recordedAt),
                GigReplaced(first.id, second.id, recordedAt), GigReplaced(second.id, third.id, recordedAt),
            )
        )

        expectThat(log.currentGigs()).isEqualTo(listOf(third))
    }

    @Test
    fun `reads a relisted gig's genre from the gig it replaced`() {
        val moved = gigAt("https://example.com/lola-aus")
        val listed = gigAt("https://example.com/eo6xmw-lola-aus-lucky-hit")
        val log = gigsLog(
            listOf(
                GigObserved(moved, recordedAt),
                GigClassified(moved.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
                GigObserved(listed, recordedAt),
                GigReplaced(moved.id, listed.id, recordedAt),
            )
        )

        expectThat(log.classificationStatus()[listed.id]).isEqualTo(ClassificationStatus.Classified(Genre.Metal))
        expectThat(log.metalGigs()).isEqualTo(listOf(listed))
    }

    // the verdict was recorded against the first url of three, and it is the gig at the third that
    // gets published
    @Test
    fun `reads a genre back through a gig relisted twice`() {
        val first = gigAt("https://example.com/lola-aus")
        val second = gigAt("https://example.com/lola-aus-lucky-hit")
        val third = gigAt("https://example.com/lola-aus-lucky-hit-and-support")
        val log = gigsLog(
            listOf(
                GigObserved(first, recordedAt),
                GigClassified(first.id, recordedAt, Genre.Metal, ClassificationSource.User),
                GigObserved(second, recordedAt), GigObserved(third, recordedAt),
                GigReplaced(first.id, second.id, recordedAt), GigReplaced(second.id, third.id, recordedAt),
            )
        )

        expectThat(log.classificationStatus()[third.id]).isEqualTo(ClassificationStatus.Classified(Genre.Metal))
        expectThat(log.overriddenByUser()).contains(third.id)
    }

    // what classify skips, so a venue rewriting a title costs no paid call - and what a forced run
    // skips, so it doesn't ask an LLM about a gig whose genre a user typed at its old url
    @Test
    fun `counts a relisted gig as classified already, and as overridden if its old url was`() {
        val moved = gigAt("https://example.com/lola-aus")
        val listed = gigAt("https://example.com/eo6xmw-lola-aus-lucky-hit")
        val llmJudged = gigsLog(listOf(GigClassified(moved.id, recordedAt, Genre.Metal, ClassificationSource.LLM), GigReplaced(moved.id, listed.id, recordedAt)))

        expectThat(llmJudged.alreadyClassified()).contains(listed.id)
        expectThat(llmJudged.overriddenByUser()).isEqualTo(emptySet())
    }

    // the gig at the new url has been judged on its own text since it moved, and that is the verdict
    // about the gig as it is listed now
    @Test
    fun `prefers a relisted gig's own classification to the one it inherits`() {
        val moved = gigAt("https://example.com/lola-aus")
        val listed = gigAt("https://example.com/eo6xmw-lola-aus-lucky-hit")
        val log = gigsLog(
            listOf(
                GigClassified(moved.id, recordedAt, Genre.Metal, ClassificationSource.LLM),
                GigReplaced(moved.id, listed.id, recordedAt),
                GigClassified(listed.id, recordedAt, Genre.Other, ClassificationSource.User),
            )
        )

        expectThat(log.classificationStatus()[listed.id]).isEqualTo(ClassificationStatus.Classified(Genre.Other))
    }
    // A gig moved between rooms is a gig at another venue, and nothing about a replacement says the
    // two are the same venue's - what makes them one gig is the venue saying so, not where it is.
    @Test
    fun `reads back a replacement whose two gigs are at different venues`() {
        val moved = GigId(VenueId("Blondies Bar"), GigUrl("https://example.com/lola-aus"))
        val listed = GigId(VenueId("Blondies Brewery Taproom"), GigUrl("https://example.com/eo6xmw-lola-aus"))
        val file = File.createTempFile("events", ".ndjson").apply { deleteOnExit() }

        val log = GigsLog(file).apply { append(listOf(GigReplaced(moved, listed, recordedAt))) }

        expectThat(GigsLog(file).entries).isEqualTo(log.entries)
        expectThat(GigsLog(file).entries.filterIsInstance<GigReplaced>().single().by.venueId).isEqualTo(VenueId("Blondies Brewery Taproom"))
    }
}
