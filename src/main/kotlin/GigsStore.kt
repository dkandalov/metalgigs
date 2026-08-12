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

object JGigEvent : JAny<GigEvent>() {
    private val title by str(GigEvent::title)
    private val venue by str(GigEvent::venue)
    private val year by num(GigEvent::year)
    private val month by str(GigEvent::month)
    private val day by str(GigEvent::day)
    private val url by str(GigEvent::url)
    private val imageUrl by str(GigEvent::imageUrl)

    override fun JsonNodeObject.deserializeOrThrow() = GigEvent(
        title = +title,
        venue = +venue,
        year = +year,
        month = +month,
        day = +day,
        url = +url,
        imageUrl = +imageUrl,
    )
}

object JGigObserved : JAny<GigObserved>() {
    private val gig by obj(JGigEvent, GigObserved::gig)
    private val recordedAt by str(GigObserved::recordedAt)
    // optional, so entries written before pageText existed still read back (see GigObserved)
    private val pageText by str(GigObserved::pageText)

    override fun JsonNodeObject.deserializeOrThrow() = GigObserved(
        gig = +gig,
        recordedAt = +recordedAt,
        pageText = +pageText,
    )
}

// GigId is flattened to venue/url rather than nested, matching how GigObserved's own gig object
// carries them and keeping the on-disk format unchanged
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

object JGigLogEntry : JSealed<GigLogEntry>() {
    override val subConverters: Map<String, ObjectNodeConverter<out GigLogEntry>> = mapOf(
        "observed" to JGigObserved,
        "classified" to JGigClassified,
    )

    override fun extractTypeName(obj: GigLogEntry): String = when (obj) {
        is GigObserved -> "observed"
        is GigClassified -> "classified"
    }
}

fun appendGigLogEntries(file: File, entries: List<GigLogEntry>) {
    FileWriter(file, true).buffered().use { writer ->
        toNdJson(JGigLogEntry)(entries).forEach { writer.appendLine(it) }
    }
}

fun readGigLogEntries(file: File): List<GigLogEntry> =
    fromNdJsonToList(JGigLogEntry)(file.readLines().asSequence()).orThrow()

fun projectCurrentGigs(entries: List<GigLogEntry>): List<GigEvent> =
    entries.filterIsInstance<GigObserved>()
        .groupBy { it.id }
        .values
        .map { observations -> observations.maxBy { it.recordedAt }.gig }

// scraped gigs not yet in the log, or that differ from their latest logged observation (e.g. a
// title gaining "- SOLD OUT", a rescheduled date) - compares against only the latest observation
// per gig, not the whole history, so a gig can be logged again after reverting to a prior state
fun newOrChangedGigs(existingEntries: List<GigLogEntry>, scrapedGigs: List<GigEvent>): List<GigEvent> {
    val latestByGig = projectCurrentGigs(existingEntries).associateBy { it.id }
    return scrapedGigs.filter { gig -> latestByGig[gig.id] != gig }
}

// each gig's most recently captured event-page text. Falls back through older observations rather
// than only reading the latest one, so a gig re-observed by a scrape that couldn't reach its page
// keeps the text an earlier scrape did capture
fun pageTextByGig(entries: List<GigLogEntry>): Map<GigId, String> =
    entries.filterIsInstance<GigObserved>()
        .filter { it.pageText != null }
        .groupBy { it.id }
        .mapValues { (_, observations) -> observations.maxBy { it.recordedAt }.pageText!! }

// when each venue was last seen changing - an approximation of "last scraped" derived from
// GigObserved entries rather than a dedicated scrape-event type; a venue with no changes for
// longer than the cooldown looks stale here and gets rescraped anyway, which just means it's
// scraped a bit more often than strictly necessary, never less
fun lastScrapedAt(entries: List<GigLogEntry>): Map<String, Instant> =
    entries.filterIsInstance<GigObserved>()
        .groupBy { it.id.venue }
        .mapValues { (_, observations) -> observations.maxOf { it.recordedAt } }

// has a poster from this source url already been ingested? - every gig from one poster shares a
// "{sourceUrl}#..." url (see posterGigUrl), so one prefix check covers the whole poster
fun alreadyIngested(entries: List<GigLogEntry>, sourceUrl: String): Boolean =
    entries.any { it.id.url.startsWith("$sourceUrl#") }

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

fun classificationStatusByGig(entries: List<GigLogEntry>): Map<GigId, ClassificationStatus> =
    entries.filterIsInstance<GigClassified>()
        .groupBy { it.id }
        .mapValues { (_, classifications) -> classificationStatus(classifications) }

// current gigs settled as Metal, by the classifier's verdict or a user's own call
fun projectMetalGigs(entries: List<GigLogEntry>): List<GigEvent> {
    val statusByGig = classificationStatusByGig(entries)
    return projectCurrentGigs(entries).filter { gig ->
        (statusByGig[gig.id] as? ClassificationStatus.Classified)?.genre == Genre.Metal
    }
}

// gigs the classifier should skip: it has already judged them, or a user has settled them
fun alreadyClassified(entries: List<GigLogEntry>): Set<GigId> =
    entries.filterIsInstance<GigClassified>().map { it.id }.toSet()
