import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.JSealed
import com.ubertob.kondor.json.ObjectNodeConverter
import com.ubertob.kondor.json.datetime.str
import com.ubertob.kondor.json.fromNdJsonToList
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.num
import com.ubertob.kondor.json.obj
import com.ubertob.kondor.json.str
import com.ubertob.kondor.json.toNdJson
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDate

// here and in JGigClassified, GigId is flattened to venue/url rather than nested - the on-disk
// format predates GigId existing, and keeping it that way means the log needs no migration
object JGigEvent : JAny<GigEvent>() {
    private val title by str(GigEvent::title)
    private val venue by str(fun GigEvent.(): String = id.venue)
    private val year by num(GigEvent::year)
    private val month by str(GigEvent::month)
    private val day by str(GigEvent::day)
    private val url by str(fun GigEvent.(): String = id.url)
    private val imageUrl by str(GigEvent::imageUrl)
    // optional, so entries written before page text was captured still read back (see GigEvent)
    private val pageText by str(GigEvent::pageText)

    override fun JsonNodeObject.deserializeOrThrow() = GigEvent(
        id = GigId(+venue, +url),
        title = +title,
        year = +year,
        month = +month,
        day = +day,
        imageUrl = +imageUrl,
        pageText = +pageText,
    )
}

object JGigObserved : JAny<GigObserved>() {
    private val gig by obj(JGigEvent, GigObserved::gig)
    private val recordedAt by str(GigObserved::recordedAt)

    override fun JsonNodeObject.deserializeOrThrow() = GigObserved(
        gig = +gig,
        recordedAt = +recordedAt,
    )
}

object JGigClassified : JAny<GigClassified>() {
    private val venue by str(fun GigClassified.(): String = id.venue)
    private val url by str(fun GigClassified.(): String = id.url)
    private val recordedAt by str(GigClassified::recordedAt)
    private val genre by str(GigClassified::genre)
    private val source by str(GigClassified::source)

    override fun JsonNodeObject.deserializeOrThrow() = GigClassified(
        id = GigId(+venue, +url),
        recordedAt = +recordedAt,
        genre = +genre,
        source = +source,
    )
}

object JGigsRendered : JAny<GigsRendered>() {
    private val file by str(GigsRendered::file)
    private val gigCount by num(GigsRendered::gigCount)
    private val logicalDate by str(GigsRendered::logicalDate)
    private val recordedAt by str(GigsRendered::recordedAt)

    override fun JsonNodeObject.deserializeOrThrow() = GigsRendered(
        file = +file,
        gigCount = +gigCount,
        logicalDate = +logicalDate,
        recordedAt = +recordedAt,
    )
}

object JLogEntry : JSealed<LogEntry>() {
    override val subConverters: Map<String, ObjectNodeConverter<out LogEntry>> = mapOf(
        "observed" to JGigObserved,
        "classified" to JGigClassified,
        "rendered" to JGigsRendered,
    )

    override fun extractTypeName(obj: LogEntry): String = when (obj) {
        is GigObserved -> "observed"
        is GigClassified -> "classified"
        is GigsRendered -> "rendered"
    }
}

fun appendLogEntries(file: File, entries: List<LogEntry>) {
    FileWriter(file, true).buffered().use { writer ->
        toNdJson(JLogEntry)(entries).forEach { writer.appendLine(it) }
    }
}

fun readLogEntries(file: File): List<LogEntry> =
    fromNdJsonToList(JLogEntry)(file.readLines().asSequence()).orThrow()

fun projectCurrentGigs(entries: List<LogEntry>): List<GigEvent> =
    entries.filterIsInstance<GigObserved>()
        .groupBy { it.id }
        .values
        .map { observations -> observations.maxBy { it.recordedAt }.gig }

// what makes a gig "the same gig in the same state" - every field the venue's listing gave us, but
// not pageText. That comes from a different page, and measurably churns: re-reading every gig's
// page minutes apart changed the text of one (a counter ticking over) and flipped eight from empty
// to full (a flaky JS-rendered site). Comparing it would log a fresh observation of an otherwise
// untouched gig each time that happened
private fun GigEvent.listedDetails() = copy(pageText = null)

// scraped gigs not yet in the log, or that differ from their latest logged observation (e.g. a
// title gaining "- SOLD OUT", a rescheduled date) - compares against only the latest observation
// per gig, not the whole history, so a gig can be logged again after reverting to a prior state
fun newOrChangedGigs(existingEntries: List<LogEntry>, scrapedGigs: List<GigEvent>): List<GigEvent> {
    val latestByGig = projectCurrentGigs(existingEntries).associateBy { it.id }
    return scrapedGigs.filter { gig -> latestByGig[gig.id]?.listedDetails() != gig.listedDetails() }
}

fun gigsMissingPageText(entries: List<LogEntry>): Set<GigId> =
    projectCurrentGigs(entries).filter { it.pageText == null }.map { it.id }.toSet()

// when each venue was last seen changing - an approximation of "last scraped" derived from
// GigObserved entries rather than a dedicated scrape-event type; a venue with no changes for
// longer than the cooldown looks stale here and gets rescraped anyway, which just means it's
// scraped a bit more often than strictly necessary, never less
fun lastScrapedAt(entries: List<LogEntry>): Map<String, Instant> =
    entries.filterIsInstance<GigObserved>()
        .groupBy { it.id.venue }
        .mapValues { (_, observations) -> observations.maxOf { it.recordedAt } }

// every gig from one poster shares a "{sourceUrl}#..." url (see posterGigUrl), so one prefix check
// covers the whole poster
fun alreadyIngested(entries: List<LogEntry>, sourceUrl: String): Boolean =
    entries.filterIsInstance<GigObserved>().any { it.id.url.startsWith("$sourceUrl#") }

// has the page already been rendered for this date? Matches on logicalDate rather than the newest
// render's own timestamp, so a backdated render of some past date doesn't count as having done
// today, and today's render still counts however long ago in the day it happened
fun alreadyRenderedFor(entries: List<LogEntry>, date: LocalDate): Boolean =
    entries.filterIsInstance<GigsRendered>().any { it.logicalDate == date }

sealed interface ClassificationStatus {
    data class Classified(val genre: Genre) : ClassificationStatus

    data object Pending : ClassificationStatus {
        override fun toString() = "Pending (not yet classified)"
    }
}

// a user's own classification is always final; otherwise the latest LLM classification stands
private fun classificationStatus(classifications: List<GigClassified>): ClassificationStatus {
    val latestBySource = classifications.groupBy { it.source }.mapValues { (_, cs) -> cs.maxBy { it.recordedAt } }
    val latest = latestBySource[ClassificationSource.User] ?: latestBySource[ClassificationSource.LLM]
    return latest?.let { ClassificationStatus.Classified(it.genre) } ?: ClassificationStatus.Pending
}

fun classificationStatusByGig(entries: List<LogEntry>): Map<GigId, ClassificationStatus> =
    entries.filterIsInstance<GigClassified>()
        .groupBy { it.id }
        .mapValues { (_, classifications) -> classificationStatus(classifications) }

fun projectMetalGigs(entries: List<LogEntry>): List<GigEvent> {
    val statusByGig = classificationStatusByGig(entries)
    return projectCurrentGigs(entries).filter { gig ->
        (statusByGig[gig.id] as? ClassificationStatus.Classified)?.genre == Genre.Metal
    }
}

fun alreadyClassified(entries: List<LogEntry>): Set<GigId> =
    entries.filterIsInstance<GigClassified>().map { it.id }.toSet()
