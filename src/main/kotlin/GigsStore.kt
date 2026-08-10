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
    private val scrapedAt by str(GigObserved::scrapedAt)

    override fun JsonNodeObject.deserializeOrThrow() = GigObserved(
        gig = +gig,
        scrapedAt = +scrapedAt,
    )
}

object JGigClassified : JAny<GigClassified>() {
    private val venue by str(GigClassified::venue)
    private val url by str(GigClassified::url)
    private val scrapedAt by str(GigClassified::scrapedAt)
    private val matchedKeywords by array(GigClassified::matchedKeywords)

    override fun JsonNodeObject.deserializeOrThrow() = GigClassified(
        venue = +venue,
        url = +url,
        scrapedAt = +scrapedAt,
        matchedKeywords = +matchedKeywords,
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
        .map { observations -> observations.maxBy { it.scrapedAt }.gig }

// current gigs with no matched keywords, including ones never classified at all
fun projectUnclassifiedGigs(entries: List<GigLogEntry>): List<GigEvent> {
    val latestClassificationByGig = entries.filterIsInstance<GigClassified>()
        .groupBy { it.venue to it.url }
        .mapValues { (_, classifications) -> classifications.maxBy { it.scrapedAt } }

    return projectCurrentGigs(entries).filter { gig ->
        latestClassificationByGig[gig.venue to gig.url]?.genre() != Genre.Metal
    }
}
