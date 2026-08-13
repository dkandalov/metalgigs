import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.JSealed
import com.ubertob.kondor.json.JStringRepresentable
import com.ubertob.kondor.json.bool
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

private object JVenue : JStringRepresentable<Venue>() {
    override val cons: (String) -> Venue = ::Venue
    override val render: (Venue) -> String = Venue::name
}

object JGig : JAny<Gig>() {
    private val title by str(Gig::title)
    private val venue by str(JVenue) { id.venue }
    private val date by str(Gig::date)
    private val url by str(fun Gig.(): String = id.url)
    private val imageUrl by str(Gig::imageUrl)
    // The lambda form forces Kondor's optional-field overload despite description not being String? -
    // a plain Gig::description reference would resolve to the required-field one instead.
    private val description by str(fun Gig.(): String? = description)

    override fun JsonNodeObject.deserializeOrThrow() = Gig(
        id = GigId(+venue, +url),
        title = +title,
        date = +date,
        imageUrl = +imageUrl,
        description = +description ?: "",
    )
}

object JGigObserved : JAny<GigObserved>() {
    private val gig by obj(JGig, GigObserved::gig)
    private val recordedAt by str(GigObserved::recordedAt)

    override fun JsonNodeObject.deserializeOrThrow() = GigObserved(
        gig = +gig,
        recordedAt = +recordedAt,
    )
}

object JGigClassified : JAny<GigClassified>() {
    private val venue by str(JVenue) { id.venue }
    private val url by str(fun GigClassified.(): String = id.url)
    private val recordedAt by str(GigClassified::recordedAt)
    private val genre by str(GigClassified::genre)
    private val source by str(GigClassified::source)
    // optional, so entries written before these existed still read back (see GigClassified)
    private val llmModel by str(GigClassified::llmModel)
    private val useVision by bool(GigClassified::useVision)

    override fun JsonNodeObject.deserializeOrThrow() = GigClassified(
        id = GigId(+venue, +url),
        recordedAt = +recordedAt,
        genre = +genre,
        source = +source,
        llmModel = +llmModel,
        useVision = +useVision,
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

fun readLogEntries(file: File): List<LogEntry> =
    fromNdJsonToList(JLogEntry)(file.readLines().asSequence()).orThrow()

sealed interface ClassificationStatus {
    data class Classified(val genre: Genre) : ClassificationStatus

    data object Pending : ClassificationStatus {
        override fun toString() = "Pending (not yet classified)"
    }
}

// wraps events.ndjson so callers don't thread a List<LogEntry> through several functions by hand -
// entries are loaded once and appending updates the same in-memory copy, so e.g. a status computed
// right after an append reflects it without the caller re-reading the file or concatenating lists
class GigsLog(private val file: File) {
    private var entries: List<LogEntry> = if (file.exists()) readLogEntries(file) else emptyList()

    fun append(newEntries: List<LogEntry>) {
        FileWriter(file, true).buffered().use { writer ->
            toNdJson(JLogEntry)(newEntries).forEach { writer.appendLine(it) }
        }
        entries = entries + newEntries
    }

    fun currentGigs(): List<Gig> =
        entries.filterIsInstance<GigObserved>()
            .groupBy { it.id }
            .values
            .map { observations -> observations.maxBy { it.recordedAt }.gig }

    // What makes a gig "the same gig in the same state" - every field the venue's listing gave us, but
    // not description. That comes from a different page, and measurably churns: re-reading every gig's
    // page minutes apart changed the text of one (a counter ticking over) and flipped eight from empty
    // to full (a flaky JS-rendered site). Comparing it would log a fresh observation of an otherwise
    // untouched gig each time that happened.
    private fun Gig.listedDetails() = copy(description = "")

    // scraped gigs not yet in the log, or that differ from their latest logged observation (e.g. a
    // title gaining "- SOLD OUT", a rescheduled date) - compares against only the latest observation
    // per gig, not the whole history, so a gig can be logged again after reverting to a prior state
    fun newOrChangedGigs(scrapedGigs: List<Gig>): List<Gig> {
        val latestByGig = currentGigs().associateBy { it.id }
        return scrapedGigs.filter { gig -> latestByGig[gig.id]?.listedDetails() != gig.listedDetails() }
    }

    // when each venue was last seen changing - an approximation of "last scraped" derived from
    // GigObserved entries rather than a dedicated scrape-event type; a venue with no changes for
    // longer than the cooldown looks stale here and gets rescraped anyway, which just means it's
    // scraped a bit more often than strictly necessary, never less
    fun lastScrapedAt(): Map<Venue, Instant> =
        entries.filterIsInstance<GigObserved>()
            .groupBy { it.id.venue }
            .mapValues { (_, observations) -> observations.maxOf { it.recordedAt } }

    // every gig from one poster shares a "{sourceUrl}#..." url (see posterGigUrl), so one prefix check
    // covers the whole poster
    fun alreadyIngested(sourceUrl: String): Boolean =
        entries.filterIsInstance<GigObserved>().any { it.id.url.startsWith("$sourceUrl#") }

    // has the page already been rendered for this date? Matches on logicalDate rather than the newest
    // render's own timestamp, so a backdated render of some past date doesn't count as having done
    // today, and today's render still counts however long ago in the day it happened
    fun alreadyRenderedFor(date: LocalDate): Boolean =
        entries.filterIsInstance<GigsRendered>().any { it.logicalDate == date }

    // a user's own classification is always final; otherwise the latest LLM classification stands
    private fun statusFor(classifications: List<GigClassified>): ClassificationStatus {
        val latestBySource = classifications.groupBy { it.source }.mapValues { (_, cs) -> cs.maxBy { it.recordedAt } }
        val latest = latestBySource[ClassificationSource.User] ?: latestBySource[ClassificationSource.LLM]
        return latest?.let { ClassificationStatus.Classified(it.genre) } ?: ClassificationStatus.Pending
    }

    fun classificationStatus(): Map<GigId, ClassificationStatus> =
        entries.filterIsInstance<GigClassified>()
            .groupBy { it.id }
            .mapValues { (_, classifications) -> statusFor(classifications) }

    fun metalGigs(): List<Gig> {
        val statusByGig = classificationStatus()
        return currentGigs().filter { gig ->
            (statusByGig[gig.id] as? ClassificationStatus.Classified)?.genre == Genre.Metal
        }
    }

    fun alreadyClassified(): Set<GigId> =
        entries.filterIsInstance<GigClassified>().map { it.id }.toSet()
}
