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
        .groupBy { it.venue to it.url }
        .values
        .map { observations -> observations.maxBy { it.recordedAt }.gig }

// scraped gigs not yet in the log, or that differ from their latest logged observation (e.g. a
// title gaining "- SOLD OUT", a rescheduled date) - compares against only the latest observation
// per gig, not the whole history, so a gig can be logged again after reverting to a prior state
fun newOrChangedGigs(existingEntries: List<GigLogEntry>, scrapedGigs: List<GigEvent>): List<GigEvent> {
    val latestByGig = projectCurrentGigs(existingEntries).associateBy { it.venue to it.url }
    return scrapedGigs.filter { gig -> latestByGig[gig.venue to gig.url] != gig }
}

private fun latestClassificationByGig(entries: List<GigLogEntry>): Map<Pair<String, String>, GigClassified> =
    entries.filterIsInstance<GigClassified>()
        .groupBy { it.venue to it.url }
        .mapValues { (_, classifications) -> classifications.maxBy { it.recordedAt } }

// current gigs whose latest classification is Metal; excludes ones never classified at all
fun projectMetalGigs(entries: List<GigLogEntry>): List<GigEvent> {
    val latestClassificationByGig = latestClassificationByGig(entries)
    return projectCurrentGigs(entries).filter { gig ->
        latestClassificationByGig[gig.venue to gig.url]?.genre == Genre.Metal
    }
}

// current gigs whose latest classification is Other, plus ones never classified at all
fun projectUnclassifiedGigs(entries: List<GigLogEntry>): List<GigEvent> {
    val latestClassificationByGig = latestClassificationByGig(entries)
    return projectCurrentGigs(entries).filter { gig ->
        latestClassificationByGig[gig.venue to gig.url]?.genre != Genre.Metal
    }
}
