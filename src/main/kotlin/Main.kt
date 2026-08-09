import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.template.HandlebarsTemplates
import java.io.File
import java.time.LocalDate

fun fetchPage(client: HttpHandler, url: String, headers: List<Pair<String, String>> = emptyList()): String =
    client(headers.fold(Request(GET, url)) { request, (name, value) -> request.header(name, value) }).bodyString()

private val gigsFile = File("gigs.ndjson")
private val imagesDir = File("images")

fun scrapeGigs() {
    val client = ClientFilters.FollowRedirects().then(OkHttp())
    val sources: List<GigsSource> = listOf(
        CartAndHorsesGigsSource(client, year = LocalDate.now().year),
        NewCrossInnGigsSource(client),
        OurBlackHeartGigsSource(client),
        TheUnderworldGigsSource(client),
    )

    val gigs = sources.flatMap { it.latestGigs() }
    gigs.forEach { println(it) }
    writeGigsNdJson(gigsFile, gigs)
    cacheGigImages(client, gigs, imagesDir)
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
