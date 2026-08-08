import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
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

    sources.flatMap { it.latestGigs() }.forEach { println(it) }
}
