import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import java.io.File
import java.time.LocalDate

fun fetchPage(client: HttpHandler, url: String): String =
    client(Request(GET, url)).bodyString()

fun main() {
    val client = OkHttp()
    val sources: List<GigsSource> = listOf(
        CartAndHorsesGigsSource(client, year = LocalDate.now().year),
        NewCrossInnGigsSource(client),
        OurBlackHeartGigsSource(client),
    )

    val gigs = sources.flatMap { it.latestGigs() }
    gigs.forEach { println(it) }
    writeGigsNdJson(File("gigs.ndjson"), gigs)
}
