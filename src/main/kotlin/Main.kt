import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.AnthropicAI
import org.http4k.ai.model.ApiKey
import org.http4k.ai.model.SystemPrompt
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
    "blondies-taproom" to BlondiesBreweryTaproomGigsSource(client),
    "blondies-bar" to BlondiesBarGigsSource(client),
    "helgis" to HelgisGigsSource(client),
    "electric-ballroom" to ElectricBallroomGigsSource(client, year = LocalDate.now().year),
    "dingwalls" to DingwallsGigsSource(client),
    "the-garage" to TheGarageGigsSource(client),
)

fun scrapeGigs(venueKeys: Set<String> = emptySet()) {
    val client = ClientFilters.FollowRedirects().then(OkHttp())
    val sourcesByKey = sourcesByKey(client)

    val unknownKeys = venueKeys - sourcesByKey.keys
    check(unknownKeys.isEmpty()) { "Unknown venue key(s): $unknownKeys. Known venue keys: ${sourcesByKey.keys}" }
    val sources = if (venueKeys.isEmpty()) sourcesByKey.values.toList() else venueKeys.map { sourcesByKey.getValue(it) }

    val gigs = sources.flatMap { it.latestGigs() }
    gigs.forEach { println(it) }

    val existingEntries = if (eventsFile.exists()) readGigLogEntries(eventsFile) else emptyList()
    val newOrChanged = newOrChangedGigs(existingEntries, gigs)
    appendGigLogEntries(eventsFile, newOrChanged.map { GigObserved(it, Instant.now()) })
}

fun classifyUnclassifiedGigs(limit: Int? = null) {
    val client = ClientFilters.FollowRedirects().then(OkHttp())
    val existingEntries = if (eventsFile.exists()) readGigLogEntries(eventsFile) else emptyList()
    val currentGigs = projectCurrentGigs(existingEntries)
    val recordedAt = Instant.now()

    val apiKey = ApiKey.of(
        System.getenv("ANTHROPIC_API_KEY")
            ?: error("ANTHROPIC_API_KEY environment variable is required for LLM classification")
    )
    val chat = Chat.AnthropicAI(apiKey = apiKey, http = client, systemPrompt = SystemPrompt.of(llmClassifierSystemPrompt))

    val keywordsClassifications = classifyGigs(
        currentGigs, alreadyClassifiedBy(existingEntries, ClassificationSource.Keywords), limit,
    ) { gig -> classifyGigByKeywords(client, gig, recordedAt) }
    appendGigLogEntries(eventsFile, keywordsClassifications)
    val afterKeywords = existingEntries + keywordsClassifications

    val llmClassifications = classifyGigs(
        currentGigs, alreadyClassifiedBy(afterKeywords, ClassificationSource.LLM), limit,
    ) { gig -> classifyGigByLLM(client, chat, gig, recordedAt) }
    appendGigLogEntries(eventsFile, llmClassifications)
    val afterLLM = afterKeywords + llmClassifications

    val affectedKeys = (keywordsClassifications + llmClassifications).map { it.venue to it.url }.toSet()
    val newlyMetalGigs = projectMetalGigs(afterLLM).filter { (it.venue to it.url) in affectedKeys }
    cacheGigImages(client, newlyMetalGigs, imagesDir)
}

fun overrideGigGenre(url: String, genre: Genre) {
    val entries = readGigLogEntries(eventsFile)
    val gig = projectCurrentGigs(entries).find { it.url == url }
        ?: error("No current gig found with url $url")

    appendGigLogEntries(eventsFile, listOf(
        GigClassified(venue = gig.venue, url = gig.url, recordedAt = Instant.now(), genre = genre, source = ClassificationSource.User),
    ))

    if (genre == Genre.Metal) {
        val client = ClientFilters.FollowRedirects().then(OkHttp())
        cacheGigImages(client, listOf(gig), imagesDir)
    }
}

fun reportUnclassifiedGigs(limit: Int? = null) {
    val allGigs = projectUnclassifiedGigs(readGigLogEntries(eventsFile)).sortedBy { it.date() }
    val gigs = if (limit != null) allGigs.take(limit) else allGigs

    gigs.groupBy { it.venue }.forEach { (venue, venueGigs) ->
        println("$venue (${venueGigs.size})")
        println()
        venueGigs.forEach { gig ->
            println("  ${gig.day} ${gig.month} ${gig.year}  ${gig.title}")
            println("  ${gig.url}")
            println()
        }
    }
    val suffix = if (limit != null) " (of ${allGigs.size} total)" else ""
    println("${gigs.size} unclassified gig(s)$suffix")
}

fun pruneOrphanedImages() {
    val metalGigs = projectMetalGigs(readGigLogEntries(eventsFile))
    val imageFiles = imagesDir.listFiles()?.toList() ?: emptyList()
    val orphaned = orphanedImageFiles(metalGigs, imageFiles)

    orphaned.forEach { file ->
        println(file.name)
        file.delete()
    }
    println("${orphaned.size} orphaned image(s) removed")
}

fun renderGigsHtml(today: LocalDate = LocalDate.now()) {
    val renderer = HandlebarsTemplates().CachingClasspath()
    val entries = readGigLogEntries(eventsFile)
    val gigs = excludeGigsInThePast(projectMetalGigs(entries), today)
    File("index.html").writeText(renderer(GigsView(groupGigsByDate(gigs))))
}

fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "all"

    when (mode) {
        "scrape" -> scrapeGigs(venueKeys = args.drop(1).toSet())
        "classify" -> classifyUnclassifiedGigs(limit = args.getOrNull(1)?.toIntOrNull())
        "render" -> {
            val today = args.drop(1).firstNotNullOfOrNull { arg -> runCatching { LocalDate.parse(arg) }.getOrNull() }
            renderGigsHtml(today = today ?: LocalDate.now())
        }
        "unclassified" -> reportUnclassifiedGigs(limit = args.getOrNull(1)?.toIntOrNull())
        "override" -> {
            val url = args.getOrNull(1)
            val genre = args.getOrNull(2)?.let { arg -> Genre.entries.find { it.name.equals(arg, ignoreCase = true) } }
            if (url == null || genre == null) {
                println("Usage: override <url> <${Genre.entries.joinToString("|") { it.name.lowercase() }}>")
            } else {
                overrideGigGenre(url, genre)
            }
        }
        "prune-images" -> pruneOrphanedImages()
        "all" -> {
            scrapeGigs()
            classifyUnclassifiedGigs()
            renderGigsHtml()
        }
        else -> println("Usage: [scrape [venue-key...]|classify [limit]|render [yyyy-mm-dd]|unclassified [limit]|override <url> <genre>|prune-images|all]")
    }
}
