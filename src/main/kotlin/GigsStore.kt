import com.ubertob.kondor.json.JAny
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
    private val scrapedAt by str(GigObserved::scrapedAt)

    override fun JsonNodeObject.deserializeOrThrow() = GigObserved(
        gig = +gig,
        scrapedAt = +scrapedAt,
    )
}

fun appendGigObservations(file: File, gigs: List<GigEvent>, scrapedAt: Instant) {
    val observations = gigs.map { GigObserved(it, scrapedAt) }
    FileWriter(file, true).buffered().use { writer ->
        toNdJson(JGigObserved)(observations).forEach { writer.appendLine(it) }
    }
}

fun readGigObservations(file: File): List<GigObserved> =
    fromNdJsonToList(JGigObserved)(file.readLines().asSequence()).orThrow()

fun projectCurrentGigs(events: List<GigObserved>): List<GigEvent> =
    events.groupBy { it.gig.venue to it.gig.url }
        .values
        .map { observations -> observations.maxBy { it.scrapedAt }.gig }
