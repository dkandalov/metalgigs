import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.JSealed
import com.ubertob.kondor.json.ObjectNodeConverter
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.datetime.str
import com.ubertob.kondor.json.fromNdJsonToList
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.num
import com.ubertob.kondor.json.obj
import com.ubertob.kondor.json.str
import com.ubertob.kondor.json.toNdJson
import java.io.File
import java.io.FileWriter

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

    override fun JsonNodeObject.deserializeOrThrow() = GigObserved(
        gig = +gig,
        recordedAt = +recordedAt,
    )
}

object JGigClassified : JAny<GigClassified>() {
    private val venue by str(GigClassified::venue)
    private val url by str(GigClassified::url)
    private val recordedAt by str(GigClassified::recordedAt)
    private val genre by str(GigClassified::genre)
    private val matchedKeywords by array(GigClassified::matchedKeywords)
    private val source by str(GigClassified::source)

    override fun JsonNodeObject.deserializeOrThrow() = GigClassified(
        venue = +venue,
        url = +url,
        recordedAt = +recordedAt,
        genre = +genre,
        matchedKeywords = +matchedKeywords,
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

sealed interface ClassificationStatus {
    data class Classified(val genre: Genre) : ClassificationStatus
    data object Disputed : ClassificationStatus
    data object Pending : ClassificationStatus
}

// a User classification is always final, regardless of what Keywords/LLM said; otherwise a gig
// is only Classified once Keywords and LLM agree (using each source's latest entry), Disputed if
// they disagree, or Pending until both have run at least once
private fun classificationStatus(classifications: List<GigClassified>): ClassificationStatus {
    val latestGenreBySource = classifications.groupBy { it.source }.mapValues { (_, cs) -> cs.maxBy { it.recordedAt }.genre }
    val userGenre = latestGenreBySource[ClassificationSource.User]
    if (userGenre != null) return ClassificationStatus.Classified(userGenre)

    val keywordsGenre = latestGenreBySource[ClassificationSource.Keywords]
    val llmGenre = latestGenreBySource[ClassificationSource.LLM]
    return when {
        keywordsGenre == null || llmGenre == null -> ClassificationStatus.Pending
        keywordsGenre == llmGenre -> ClassificationStatus.Classified(keywordsGenre)
        else -> ClassificationStatus.Disputed
    }
}

fun classificationStatusByGig(entries: List<GigLogEntry>): Map<GigId, ClassificationStatus> =
    entries.filterIsInstance<GigClassified>()
        .groupBy { it.id }
        .mapValues { (_, classifications) -> classificationStatus(classifications) }

// current gigs classified Metal by consensus: a User override, or Keywords and LLM agreeing
fun projectMetalGigs(entries: List<GigLogEntry>): List<GigEvent> {
    val statusByGig = classificationStatusByGig(entries)
    return projectCurrentGigs(entries).filter { gig ->
        (statusByGig[gig.id] as? ClassificationStatus.Classified)?.genre == Genre.Metal
    }
}

// gigs the given automated source should skip: it has already classified them, or a User
// override has already settled them
fun alreadyClassifiedBy(entries: List<GigLogEntry>, source: ClassificationSource): Set<GigId> =
    entries.filterIsInstance<GigClassified>()
        .filter { it.source == source || it.source == ClassificationSource.User }
        .map { it.id }
        .toSet()
