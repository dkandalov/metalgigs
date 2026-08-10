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

private fun sourcesByKey(client: HttpHandler): Map<String, GigsSource> = mapOf(
    "cart-and-horses" to CartAndHorsesGigsSource(client, year = LocalDate.now().year),
    "new-cross-inn" to NewCrossInnGigsSource(client),
    "our-black-heart" to OurBlackHeartGigsSource(client),
    "underworld" to TheUnderworldGigsSource(client),
    "dome" to DomeLondonGigsSource(client),
    "blondies" to BlondiesBreweryTaproomGigsSource(client),
)

fun scrapeGigs(venueKeys: Set<String> = emptySet()) {
    val client = ClientFilters.FollowRedirects().then(OkHttp())
    val sourcesByKey = sourcesByKey(client)

    val unknownKeys = venueKeys - sourcesByKey.keys
    check(unknownKeys.isEmpty()) { "Unknown venue key(s): $unknownKeys. Known venue keys: ${sourcesByKey.keys}" }
    val sources = if (venueKeys.isEmpty()) sourcesByKey.values.toList() else venueKeys.map { sourcesByKey.getValue(it) }

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

fun overrideGigGenre(url: String, genre: Genre) {
    val entries = readGigLogEntries(eventsFile)
    val gig = projectCurrentGigs(entries).find { it.url == url }
        ?: error("No current gig found with url $url")

    appendGigLogEntries(eventsFile, listOf(
        GigClassified(venue = gig.venue, url = gig.url, scrapedAt = Instant.now(), genre = genre, source = ClassificationSource.User),
    ))
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
        "scrape" -> scrapeGigs(venueKeys = args.drop(1).toSet())
        "classify" -> classifyUnclassifiedGigs()
        "render" -> renderGigsHtml()
        "unclassified" -> reportUnclassifiedGigs()
        "override" -> {
            val url = args.getOrNull(1)
            val genre = args.getOrNull(2)?.let { arg -> Genre.entries.find { it.name.equals(arg, ignoreCase = true) } }
            if (url == null || genre == null) {
                println("Usage: override <url> <${Genre.entries.joinToString("|") { it.name.lowercase() }}>")
            } else {
                overrideGigGenre(url, genre)
            }
        }
        "all" -> {
            scrapeGigs()
            classifyUnclassifiedGigs()
            renderGigsHtml()
        }
        else -> println("Usage: [scrape [venue-key...]|classify|render|unclassified|override <url> <genre>|all]")
    }
}
