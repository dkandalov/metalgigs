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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

fun fetchPage(client: HttpHandler, url: String, headers: List<Pair<String, String>> = emptyList()): String =
    client(headers.fold(Request(GET, url)) { request, (name, value) -> request.header(name, value) }).bodyString()

fun fetchBytes(client: HttpHandler, url: String, errorContext: String = url): ByteArray {
    val response = client(Request(GET, url))
    check(response.status.successful) { "Failed to fetch $errorContext: ${response.status}" }
    return response.body.stream.readBytes()
}

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
    "roundhouse" to RoundhouseGigsSource(client),
    "signature-brew-blackhorse-road" to SignatureBrewBlackhorseRoadGigsSource(client),
    "signature-brew-haggerston" to SignatureBrewHaggerstonGigsSource(client),
)

private val scrapeCooldown: Duration = Duration.ofDays(1)

fun scrapeGigs(venueKeys: Set<String> = emptySet(), force: Boolean = false) {
    val client = ClientFilters.FollowRedirects().then(OkHttp())
    val sourcesByKey = sourcesByKey(client)

    val unknownKeys = venueKeys - sourcesByKey.keys
    check(unknownKeys.isEmpty()) { "Unknown venue key(s): $unknownKeys. Known venue keys: ${sourcesByKey.keys}" }
    val sources = if (venueKeys.isEmpty()) sourcesByKey.values.toList() else venueKeys.map { sourcesByKey.getValue(it) }

    val existingEntries = if (eventsFile.exists()) readGigLogEntries(eventsFile) else emptyList()
    val lastScrapedAt = lastScrapedAt(existingEntries)
    val now = Instant.now()

    val (skipped, toScrape) = sources.partition { source ->
        !force && lastScrapedAt[source.venue]?.isAfter(now.minus(scrapeCooldown)) == true
    }
    skipped.forEach { source -> println("Skipping ${source.venue} - scraped within the last day; pass force to scrape anyway") }

    val gigs = toScrape.flatMap { it.latestGigs() }
    gigs.forEach { println(it) }

    val newOrChanged = newOrChangedGigs(existingEntries, gigs)
    appendGigLogEntries(eventsFile, newOrChanged.map { GigObserved(it, now) })
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

    val affectedKeys = (keywordsClassifications + llmClassifications).map { it.id }.toSet()
    val statusByGig = classificationStatusByGig(afterLLM)
    val newlyMetalGigs = projectMetalGigs(afterLLM).filter { it.id in affectedKeys }
    cacheGigImages(client, newlyMetalGigs, imagesDir)

    printClassificationSummary(keywordsClassifications, llmClassifications, newlyMetalGigs.size, affectedKeys, currentGigs, statusByGig)
}

private fun printClassificationSummary(
    keywordsClassifications: List<GigClassified>,
    llmClassifications: List<GigClassified>,
    reachedMetalConsensus: Int,
    affectedKeys: Set<GigId>,
    currentGigs: List<GigEvent>,
    statusByGig: Map<GigId, ClassificationStatus>,
) {
    val newConflicts = affectedKeys.count { key -> statusByGig[key] is ClassificationStatus.Disputed }

    println("Classified this run:")
    println("  Keywords: ${keywordsClassifications.size} (${keywordsClassifications.count { it.genre == Genre.Metal }} Metal, ${keywordsClassifications.count { it.genre == Genre.Other }} Other)")
    println("  LLM:      ${llmClassifications.size} (${llmClassifications.count { it.genre == Genre.Metal }} Metal, ${llmClassifications.count { it.genre == Genre.Other }} Other)")
    println("  Reached Metal consensus: $reachedMetalConsensus")
    println("  New conflicts (Keywords/LLM disagree): $newConflicts")
    println()

    val statuses = currentGigs.map { statusByGig[it.id] }
    val overallMetal = statuses.count { (it as? ClassificationStatus.Classified)?.genre == Genre.Metal }
    val overallOther = statuses.count { (it as? ClassificationStatus.Classified)?.genre == Genre.Other }
    val overallDisputed = statuses.count { it is ClassificationStatus.Disputed }
    val overallPending = currentGigs.size - overallMetal - overallOther - overallDisputed

    println("Overall (${currentGigs.size} current gigs):")
    println("  Metal:    $overallMetal")
    println("  Other:    $overallOther")
    println("  Pending:  $overallPending")
    println("  Disputed: $overallDisputed")
}

fun ingestPoster(imageUrl: String, sourceUrl: String, venue: String, force: Boolean = false) {
    val client = ClientFilters.FollowRedirects().then(OkHttp())
    val existingEntries = if (eventsFile.exists()) readGigLogEntries(eventsFile) else emptyList()

    if (!force && alreadyIngested(existingEntries, sourceUrl)) {
        println("Skipping - $sourceUrl already ingested; pass force to re-ingest anyway")
        return
    }

    val apiKey = ApiKey.of(
        System.getenv("ANTHROPIC_API_KEY")
            ?: error("ANTHROPIC_API_KEY environment variable is required for poster extraction")
    )
    val chat = Chat.AnthropicAI(apiKey = apiKey, http = client, systemPrompt = SystemPrompt.of(posterExtractionSystemPrompt))

    val gigs = extractPosterGigs(client, chat, imageUrl, sourceUrl, venue)
    gigs.forEach { println(it) }

    val newOrChanged = newOrChangedGigs(existingEntries, gigs)
    val recordedAt = Instant.now()
    val observed = newOrChanged.map { GigObserved(it, recordedAt) }
    val classified = newOrChanged.map { gig ->
        GigClassified(venue = gig.venue, url = gig.url, recordedAt = recordedAt, genre = Genre.Metal, source = ClassificationSource.User)
    }
    appendGigLogEntries(eventsFile, observed + classified)
    cacheGigImages(client, newOrChanged, imagesDir)

    println("${gigs.size} gig(s) extracted from poster, ${newOrChanged.size} new/changed, assumed Metal")
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

fun renderGigsHtml(today: LocalDate = LocalDate.now(), force: Boolean = false, fullUnresolved: Boolean = false) {
    val entries = readGigLogEntries(eventsFile)
    val statusByGig = classificationStatusByGig(entries)
    val upcomingGigs = excludeGigsInThePast(projectCurrentGigs(entries), today)
    val unresolved = upcomingGigs.filter { gig -> statusByGig[gig.id] !is ClassificationStatus.Classified }
        .sortedBy { it.date() }

    if (unresolved.isNotEmpty() && !force) {
        val shown = if (fullUnresolved) unresolved else unresolved.take(5)
        val listing = shown.joinToString("\n") { "  ${it.date()}  ${it.venue}  ${it.title}\n  ${it.url}" }
        val hint = if (shown.size < unresolved.size) " Soonest ${shown.size} (pass full-unresolved to see all)" else ""
        error("${unresolved.size} upcoming gig(s) not yet resolved (Pending or Disputed) - run classify/override first, or pass force to render anyway.$hint:\n$listing")
    }

    val renderer = HandlebarsTemplates().CachingClasspath()
    val gigs = excludeGigsInThePast(projectMetalGigs(entries), today)
    File("index.html").writeText(renderer(GigsView(groupGigsByDate(gigs))))
}

fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "all"

    when (mode) {
        "scrape" -> {
            val scrapeArgs = args.drop(1)
            scrapeGigs(venueKeys = (scrapeArgs - "force").toSet(), force = scrapeArgs.contains("force"))
        }
        "classify" -> classifyUnclassifiedGigs(limit = args.getOrNull(1)?.toIntOrNull())
        "render" -> {
            val renderArgs = args.drop(1)
            val today = renderArgs.firstNotNullOfOrNull { arg -> runCatching { LocalDate.parse(arg) }.getOrNull() }
            renderGigsHtml(today = today ?: LocalDate.now(), force = renderArgs.contains("force"), fullUnresolved = renderArgs.contains("full-unresolved"))
        }
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
        "ingest-poster" -> {
            val posterArgs = args.drop(1)
            val positional = posterArgs.filterNot { it == "force" }
            if (positional.size != 3) {
                println("Usage: ingest-poster <imageUrl> <sourceUrl> <venue> [force]")
            } else {
                val (imageUrl, sourceUrl, venue) = positional
                ingestPoster(imageUrl, sourceUrl, venue, force = posterArgs.contains("force"))
            }
        }
        "all" -> {
            scrapeGigs()
            classifyUnclassifiedGigs()
            renderGigsHtml()
        }
        else -> println("Usage: [scrape [venue-key...] [force]|classify [limit]|render [yyyy-mm-dd] [force] [full-unresolved]|override <url> <genre>|prune-images|ingest-poster <imageUrl> <sourceUrl> <venue> [force]|all]")
    }
}
