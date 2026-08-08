import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.fromNdJsonToList
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.num
import com.ubertob.kondor.json.str
import com.ubertob.kondor.json.toNdJson
import java.io.File

object JGigEvent : JAny<GigEvent>() {
    private val title by str(GigEvent::title)
    private val year by num(GigEvent::year)
    private val month by str(GigEvent::month)
    private val day by str(GigEvent::day)
    private val url by str(GigEvent::url)

    override fun JsonNodeObject.deserializeOrThrow() = GigEvent(
        title = +title,
        year = +year,
        month = +month,
        day = +day,
        url = +url,
    )
}

fun writeGigsNdJson(file: File, gigs: List<GigEvent>) =
    file.bufferedWriter().use { writer ->
        toNdJson(JGigEvent)(gigs).forEach { writer.appendLine(it) }
    }

fun readGigsNdJson(file: File): List<GigEvent> =
    fromNdJsonToList(JGigEvent)(file.readLines().asSequence()).orThrow()
