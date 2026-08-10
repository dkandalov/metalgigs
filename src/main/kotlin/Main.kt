import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.template.HandlebarsTemplates
import java.io.File
import java.time.Instant
import java.time.LocalDate

fun fetchPage(client: HttpHandler, url: String, headers: List<Pair<String, String>> = emptyList()): String =
    client(headers.fold(Request(GET, url)) { request, (name, value) -> request.header(name, value) }).bodyString()

private val eventsFile = File("events.ndjson")
private val imagesDir = File("images")

fun scrapeGigs() {
    val client = ClientFilters.FollowRedirects().then(OkHttp())
    val sources: List<GigsSource> = listOf(
        CartAndHorsesGigsSource(client, year = LocalDate.now().year),
        NewCrossInnGigsSource(client),
        OurBlackHeartGigsSource(client),
        TheUnderworldGigsSource(client),
        DomeLondonGigsSource(client),
    )

    val gigs = sources.flatMap { it.latestGigs() }
    gigs.forEach { println(it) }

    appendGigLogEntries(eventsFile, gigs.map { GigObserved(it, Instant.now()) })
    cacheGigImages(client, gigs, imagesDir)
}

fun classifyUnclassifiedGigs() {
    val client = ClientFilters.FollowRedirects().then(OkHttp())
    val existingEntries = if (eventsFile.exists()) readGigLogEntries(eventsFile) else emptyList()
    val currentGigs = projectCurrentGigs(existingEntries)
    val alreadyClassified = existingEntries.filterIsInstance<GigClassified>().map { it.venue to it.url }.toSet()

    val classifications = classifyGigs(client, currentGigs, alreadyClassified, scrapedAt = Instant.now())
    appendGigLogEntries(eventsFile, classifications)
}

fun reportUnclassifiedGigs() {
    val gigs = projectUnclassifiedGigs(readGigLogEntries(eventsFile))
    gigs.groupBy { it.venue }.forEach { (venue, venueGigs) ->
        println("$venue (${venueGigs.size})")
        println()
        venueGigs.forEach { gig ->
            println("  ${gig.day} ${gig.month} ${gig.year}  ${gig.title}")
            println("  ${gig.url}")
            println()
        }
    }
    println("${gigs.size} unclassified gig(s)")
}

fun renderGigsHtml() {
    val renderer = HandlebarsTemplates().CachingClasspath()
    val gigs = projectCurrentGigs(readGigLogEntries(eventsFile))
    File("gigs.html").writeText(renderer(GigsView(groupGigsByDate(gigs))))
}

fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "all"

    when (mode) {
        "scrape" -> scrapeGigs()
        "classify" -> classifyUnclassifiedGigs()
        "render" -> renderGigsHtml()
        "unclassified" -> reportUnclassifiedGigs()
        "all" -> {
            scrapeGigs()
            classifyUnclassifiedGigs()
            renderGigsHtml()
        }
        else -> println("Usage: [scrape|classify|render|unclassified|all]")
    }
}
