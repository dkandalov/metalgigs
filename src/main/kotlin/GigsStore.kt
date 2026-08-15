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

private object JVenueId : JStringRepresentable<VenueId>() {
    override val cons: (String) -> VenueId = ::VenueId
    override val render: (VenueId) -> String = VenueId::value
}

private object JGigTitle : JStringRepresentable<GigTitle>() {
    override val cons: (String) -> GigTitle = ::GigTitle
    override val render: (GigTitle) -> String = GigTitle::value
}

object JGig : JAny<Gig>() {
    private val title by str(JGigTitle) { title }
    private val venue by str(JVenueId) { id.venueId }
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
    private val venue by str(JVenueId) { id.venueId }
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

private fun readLogEntries(file: File): List<LogEntry> =
    fromNdJsonToList(JLogEntry)(file.readLines().asSequence()).orThrow()

sealed interface ClassificationStatus {
    data class Classified(val genre: Genre) : ClassificationStatus

    data object Pending : ClassificationStatus {
        override fun toString() = "Pending (not yet classified)"
    }
}

data class CompactedLog(
    val entries: List<LogEntry>,
    val observationsDropped: Int,
    val classificationsDropped: Int,
)

// wraps events.ndjson so callers don't thread a List<LogEntry> through several functions by hand -
// entries are loaded once and appending updates the same in-memory copy, so e.g. a status computed
// right after an append reflects it without the caller re-reading the file or concatenating lists
class GigsLog(private val file: File) {
    // what the file held when this was constructed, plus anything appended since
    var entries: List<LogEntry> = if (file.exists()) readLogEntries(file) else emptyList()
        private set

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

    // scraped gigs not yet in the log, or that differ from their latest logged observation (e.g. a
    // title gaining "- SOLD OUT", a rescheduled date, or a changed description) - compares against
    // only the latest observation per gig, not the whole history, so a gig can be logged again after
    // reverting to a prior state
    fun newOrChangedGigs(scrapedGigs: List<Gig>): List<Gig> {
        val latestByGig = currentGigs().associateBy { it.id }
        return scrapedGigs.filter { gig -> latestByGig[gig.id] != gig }
    }

    // when each venue was last seen changing - an approximation of "last scraped" derived from
    // GigObserved entries rather than a dedicated scrape-event type; a venue with no changes for
    // longer than the cooldown looks stale here and gets rescraped anyway, which just means it's
    // scraped a bit more often than strictly necessary, never less
    fun lastScrapedAt(): Map<VenueId, Instant> =
        entries.filterIsInstance<GigObserved>()
            .groupBy { it.id.venueId }
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
    private fun effectiveClassification(classifications: List<GigClassified>): GigClassified? {
        val latestBySource = classifications.groupBy { it.source }.mapValues { (_, cs) -> cs.maxBy { it.recordedAt } }
        return latestBySource[ClassificationSource.User] ?: latestBySource[ClassificationSource.LLM]
    }

    fun classificationStatus(): Map<GigId, ClassificationStatus> =
        entries.filterIsInstance<GigClassified>()
            .groupBy { it.id }
            .mapValues { (_, classifications) ->
                effectiveClassification(classifications)
                    ?.let { ClassificationStatus.Classified(it.genre) } ?: ClassificationStatus.Pending
            }

    fun metalGigs(): List<Gig> {
        val statusByGig = classificationStatus()
        return currentGigs().filter { gig ->
            (statusByGig[gig.id] as? ClassificationStatus.Classified)?.genre == Genre.Metal
        }
    }

    fun alreadyClassified(): Set<GigId> =
        entries.filterIsInstance<GigClassified>().map { it.id }.toSet()

    // the log is append-only, so a gig re-observed on a later scrape is logged again in full, event
    // page text and all, and every projection then reads only the newest of them. Compacting keeps
    // just that newest observation per gig, and just the one classification that decides the gig's
    // genre, which is the same one classificationStatus picks. Every render entry survives: they say
    // what was published rather than what a gig is, and there's one per render rather than per gig.
    //
    // What's lost is the history itself - when a gig gained "- SOLD OUT", was rescheduled or had its
    // text captured, and which model judged a classification since superseded.
    //
    // Hands back what to write rather than writing it, so the caller can check the compacted copy
    // projects identically before anything replaces the log it came from.
    fun compact(): CompactedLog {
        val observations = entries.filterIsInstance<GigObserved>()
            .groupBy { it.id }
            .map { (_, observations) -> observations.maxBy { it.recordedAt } }
        val classifications = entries.filterIsInstance<GigClassified>()
            .groupBy { it.id }
            .mapNotNull { (_, classifications) -> effectiveClassification(classifications) }
        val renders = entries.filterIsInstance<GigsRendered>()

        return CompactedLog(
            entries = (observations + classifications + renders).sortedBy { it.recordedAt },
            observationsDropped = entries.count { it is GigObserved } - observations.size,
            classificationsDropped = entries.count { it is GigClassified } - classifications.size,
        )
    }
}
