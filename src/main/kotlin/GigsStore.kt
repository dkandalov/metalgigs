import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.fromNdJsonToList
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.num
import com.ubertob.kondor.json.str
import com.ubertob.kondor.json.toNdJson
import java.io.File

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

fun writeGigsNdJson(file: File, gigs: List<GigEvent>) =
    file.bufferedWriter().use { writer ->
        toNdJson(JGigEvent)(gigs).forEach { writer.appendLine(it) }
    }

fun readGigsNdJson(file: File): List<GigEvent> =
    fromNdJsonToList(JGigEvent)(file.readLines().asSequence()).orThrow()
