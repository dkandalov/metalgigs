import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.template.HandlebarsTemplates
import java.io.File
import java.time.LocalDate

fun fetchPage(client: HttpHandler, url: String): String =
    client(Request(GET, url)).bodyString()

private val gigsFile = File("gigs.ndjson")

fun scrapeGigs() {
    val client = OkHttp()
    val sources: List<GigsSource> = listOf(
        CartAndHorsesGigsSource(client, year = LocalDate.now().year),
        NewCrossInnGigsSource(client),
        OurBlackHeartGigsSource(client),
    )

    val gigs = sources.flatMap { it.latestGigs() }
    gigs.forEach { println(it) }
    writeGigsNdJson(gigsFile, gigs)
}

fun renderGigsHtml() {
    val renderer = HandlebarsTemplates().CachingClasspath()
    File("gigs.html").writeText(renderer(GigsView(groupGigsByDate(readGigsNdJson(gigsFile)))))
}

fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "all"

    when (mode) {
        "scrape" -> scrapeGigs()
        "render" -> renderGigsHtml()
        "all" -> {
            scrapeGigs()
            renderGigsHtml()
        }
        else -> println("Usage: [scrape|render|all]")
    }
}
