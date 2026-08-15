import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.AnthropicAI
import org.http4k.ai.llm.model.Content
import org.http4k.ai.llm.model.Resource
import org.http4k.ai.model.ApiKey
import org.http4k.ai.model.SystemPrompt
import org.http4k.client.OkHttp
import org.http4k.connect.model.Base64Blob
import org.http4k.connect.model.MimeType
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

private fun mimeTypeForImageUrl(url: String) =
    when (imageUrlExtension(url).lowercase()) {
        "png" -> MimeType.IMAGE_PNG
        "gif" -> MimeType.IMAGE_GIF
        "webp" -> MimeType.IMAGE_WEBP
        else -> MimeType.IMAGE_JPG
    }

private const val MAX_IMAGE_BYTES = 7_000_000

fun fetchImageContent(client: HttpHandler, imageUrl: String): Content.Image {
    val response = client(Request(GET, imageUrl))
    check(response.status.successful) { "Failed to fetch image at $imageUrl: ${response.status}" }
    val mimeType = response.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
        ?.let { MimeType.of(it) } ?: mimeTypeForImageUrl(imageUrl)
    val bytes = response.body.stream.readBytes()
    check(bytes.size <= MAX_IMAGE_BYTES) {
        "Image at $imageUrl is ${bytes.size} bytes, too large to send (limit ~$MAX_IMAGE_BYTES)"
    }
    return Content.Image(Resource.Binary(Base64Blob.encode(bytes), mimeType))
}

fun fetchPosterForClassifying(client: HttpHandler, imageUrl: String): Content.Image {
    val resized = File.createTempFile("classify-poster", ".webp")
    try {
        convertToWebp(downloadToCache(client, imageUrl, imageCacheDir), resized)
        return Content.Image(Resource.Binary(Base64Blob.encode(resized.readBytes()), MimeType.IMAGE_WEBP))
    } finally {
        resized.delete()
    }
}

private val eventsFile = File("events.ndjson")
private val publishedImagesDir = File("images")
private val imageCacheDir = File(".image-cache")
private val indexFile = File("index.html")
private val renderedDir = File(".rendered")

private fun cacheImagesReportingFailures(client: HttpHandler, gigs: List<Gig>, what: String) {
    val failures = gigs.filter { it.imageUrl.isNotBlank() }.mapNotNull { gig ->
        runCatching { downloadToCache(client, gig.imageUrl, imageCacheDir) }.exceptionOrNull()
            ?.let { "${gig.date}  ${gig.id.venueId}  ${gig.title}: ${it.message}" }
    }
    if (failures.isNotEmpty()) {
        println("Could not download ${failures.size} $what:")
        failures.forEach { println("  $it") }
    }
}

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
    "o2-forum-kentish-town" to O2ForumKentishTownGigsSource(client),
    "o2-academy-brixton" to O2AcademyBrixtonGigsSource(client),
    "the-grace" to TheGraceGigsSource(client),
    "o2-academy-islington" to O2AcademyIslingtonGigsSource(client),
    "o2-shepherds-bush-empire" to O2ShepherdsBushEmpireGigsSource(client),
    "union-chapel" to UnionChapelGigsSource(client),
    "scala" to ScalaGigsSource(client),
    "229" to TwoTwoNineGigsSource(client),
    "alexandra-palace" to AlexandraPalaceGigsSource(client),
    "paper-dress-vintage" to PaperDressVintageGigsSource(client),
)

private val scrapeCooldown: Duration = Duration.ofDays(1)

fun scrapeGigs(venueKeys: Set<String> = emptySet(), force: Boolean = false) {
    val client = ClientFilters.FollowRedirects().then(OkHttp())
    val sourcesByKey = sourcesByKey(client)

    val unknownKeys = venueKeys - sourcesByKey.keys
    check(unknownKeys.isEmpty()) { "Unknown venue key(s): $unknownKeys. Known venue keys: ${sourcesByKey.keys}" }
    val sources = if (venueKeys.isEmpty()) sourcesByKey.values.toList() else venueKeys.map { sourcesByKey.getValue(it) }

    val log = GigsLog(eventsFile)
    val lastScrapedAt = log.lastScrapedAt()
    val now = Instant.now()

    val (skipped, toScrape) = sources.partition { source ->
        !force && lastScrapedAt[source.venue.name]?.isAfter(now.minus(scrapeCooldown)) == true
    }
    skipped.forEach { source -> println("Skipping ${source.venue} - scraped within the last day; pass force to scrape anyway") }

    val gigs = toScrape.flatMap { source ->
        println("Scraping ${source.venue}...")
        source.latestGigs().also { println("  ${it.size} gig(s) listed") }
    }

    val toObserve = log.newOrChangedGigs(gigs)

    val validated = likelyContaminatedVenues(gigs)
    if (validated.isNotEmpty()) {
        println("Descriptions may include site-wide boilerplate - consider scoping their source's eventPageContent. Not logging their gigs this run:")
        validated.forEach { (venue, count) -> println("  $venue ($count gig(s))") }
    }

    val observed = toObserve.filterNot { it.id.venueId.name in validated.keys }.map { gig -> GigObserved(gig, now) }
    log.append(observed)
    println("Logged ${observed.size} new or changed gig(s) of ${gigs.size} scraped")

    val withoutText = observed.filter { it.gig.description.isBlank() }
    if (withoutText.isNotEmpty()) {
        println("Could not capture event page text for ${withoutText.size} gig(s); they'll be classified from their poster instead")
        val withoutPoster = withoutText.count { it.gig.imageUrl.isBlank() }
        if (withoutPoster > 0) println("  $withoutPoster of those have no poster either, so they stay unclassified until a later scrape captures text")
    }

    cacheImagesReportingFailures(client, gigs, "gig image(s) - those gigs will have no poster")
}

fun classifyUnclassifiedGigs(limit: Int? = null) {
    val client = ClientFilters.FollowRedirects().then(OkHttp())
    val log = GigsLog(eventsFile)
    val currentGigs = log.currentGigs()
    val recordedAt = Instant.now()

    val apiKey = ApiKey.of(
        System.getenv("ANTHROPIC_API_KEY")
            ?: error("ANTHROPIC_API_KEY environment variable is required for LLM classification")
    )
    val chat = Chat.AnthropicAI(apiKey = apiKey, http = client, systemPrompt = SystemPrompt.of(llmClassifierSystemPrompt))

    val run = classifyGigs(currentGigs, log.alreadyClassified(), limit) { gig ->
        classifyGigByLLM(client, chat, gig, recordedAt)
    }
    val classifications = run.classified
    log.append(classifications)

    val affectedKeys = classifications.map { it.id }.toSet()
    val statusByGig = log.classificationStatus()
    val newlyMetalGigs = log.metalGigs().filter { it.id in affectedKeys }

    printClassificationSummary(classifications, newlyMetalGigs.size, currentGigs, statusByGig, run.failed)
}

private fun printClassificationSummary(
    classifications: List<GigClassified>,
    newlyMetal: Int,
    currentGigs: List<Gig>,
    statusByGig: Map<GigId, ClassificationStatus>,
    failed: List<Pair<Gig, String>>,
) {
    println("Classified this run: ${classifications.size} ($newlyMetal Metal, ${classifications.size - newlyMetal} Other)")
    if (failed.isNotEmpty()) {
        println("Could not classify ${failed.size} gig(s) - they stay Pending:")
        failed.forEach { (gig, reason) -> println("  ${gig.date}  ${gig.id.venueId}  ${gig.title}: $reason") }
    }
    println()

    printBreakdown("Overall", currentGigs, statusByGig)
}

private fun printBreakdown(label: String, gigs: List<Gig>, statusByGig: Map<GigId, ClassificationStatus>) {
    val statuses = gigs.map { statusByGig[it.id] }
    val metal = statuses.count { (it as? ClassificationStatus.Classified)?.genre == Genre.Metal }
    val other = statuses.count { (it as? ClassificationStatus.Classified)?.genre == Genre.Other }

    println("$label (${gigs.size} gigs):")
    println("  Metal:   $metal")
    println("  Other:   $other")
    println("  Pending: ${gigs.size - metal - other}")
}

fun printClassificationStatus(today: LocalDate = LocalDate.now()) {
    val log = GigsLog(eventsFile)
    val statusByGig = log.classificationStatus()
    val currentGigs = log.currentGigs()
    val upcoming = excludeGigsInThePast(currentGigs, today)

    printBreakdown("Overall", currentGigs, statusByGig)
    println()
    printBreakdown("Upcoming from $today", upcoming, statusByGig)

    val pendingByVenue = upcoming
        .filter { statusByGig[it.id] !is ClassificationStatus.Classified }
        .groupingBy { it.id.venueId.name }
        .eachCount()

    if (pendingByVenue.isNotEmpty()) {
        println()
        println("Upcoming Pending by venue:")
        pendingByVenue.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .forEach { (venue, count) -> println("  ${count.toString().padStart(4)}  $venue") }
    }
}

fun ingestPoster(imageUrl: String, sourceUrl: String, venue: String, force: Boolean = false) {
    val client = ClientFilters.FollowRedirects().then(OkHttp())
    val log = GigsLog(eventsFile)

    if (!force && log.alreadyIngested(sourceUrl)) {
        println("Skipping - $sourceUrl already ingested; pass force to re-ingest anyway")
        return
    }

    val apiKey = ApiKey.of(
        System.getenv("ANTHROPIC_API_KEY")
            ?: error("ANTHROPIC_API_KEY environment variable is required for poster extraction")
    )
    val chat = Chat.AnthropicAI(apiKey = apiKey, http = client, systemPrompt = SystemPrompt.of(posterExtractionSystemPrompt))

    val gigs = extractPosterGigs(client, chat, imageUrl, sourceUrl, VenueId(venue))
    gigs.forEach { println(it) }

    val newOrChanged = log.newOrChangedGigs(gigs)
    val recordedAt = Instant.now()
    val observed = newOrChanged.map { GigObserved(it, recordedAt) }
    val classified = gigs.map { gig ->
        GigClassified(id = gig.id, recordedAt = recordedAt, genre = Genre.Metal, source = ClassificationSource.User)
    }
    log.append(observed + classified)
    cacheImagesReportingFailures(client, gigs, "poster image(s)")

    println("${gigs.size} gig(s) extracted from poster (${newOrChanged.size} new/changed), all assumed Metal")
}

fun overrideGigGenre(url: String, genre: Genre) {
    val log = GigsLog(eventsFile)
    val gig = log.currentGigs().find { it.id.url == url }
        ?: error("No current gig found with url $url")

    log.append(listOf(
        GigClassified(id = gig.id, recordedAt = Instant.now(), genre = genre, source = ClassificationSource.User),
    ))
}

fun renderGigsHtml(today: LocalDate = LocalDate.now(), force: Boolean = false, fullUnresolved: Boolean = false) {
    val log = GigsLog(eventsFile)
    val statusByGig = log.classificationStatus()
    val upcomingGigs = excludeGigsInThePast(log.currentGigs(), today)
    val unresolved = upcomingGigs.filter { gig -> statusByGig[gig.id] !is ClassificationStatus.Classified }
        .sortedBy { it.date }

    if (unresolved.isNotEmpty() && !force) {
        val shown = if (fullUnresolved) unresolved else unresolved.take(5)
        val listing = shown.joinToString("\n") { gig ->
            val status = statusByGig[gig.id] ?: ClassificationStatus.Pending
            "  ${gig.date}  ${gig.id.venueId}  ${gig.title}\n  $status\n  ${gig.id.url}"
        }
        val hint = if (shown.size < unresolved.size) " Soonest ${shown.size} (pass full-unresolved to see all)" else ""
        error("${unresolved.size} upcoming gig(s) not yet classified - run classify/override first, or pass force to render anyway.$hint:\n$listing")
    }

    val metalGigs = log.metalGigs()
    val gigs = excludeGigsInThePast(metalGigs, today)
    publishGigImages(gigs, keep = metalGigs)

    val renderer = HandlebarsTemplates().CachingClasspath()
    val html = renderer(GigsView(groupGigsByDate(gigs)))

    val renderedAt = Instant.now()
    val archived = archiveRender(html, renderedDir, indexFile, renderedAt)
    log.append(listOf(GigsRendered(archived.name, gigs.size, today, renderedAt)))

    println("Rendered ${gigs.size} gig(s) as of $today to $indexFile, archived as $archived")
}

// keep is every metal gig, not just the rendered ones, so a gig dropping off the page as its date
// passes doesn't take its image with it - only an image no gig claims any more is removed
private fun publishGigImages(renderedGigs: List<Gig>, keep: List<Gig>) {
    val client = ClientFilters.FollowRedirects().then(OkHttp())

    val failures = renderedGigs.filter { it.imageUrl.isNotBlank() }.mapNotNull { gig ->
        runCatching { publishGigImage(client, gig, imageCacheDir, publishedImagesDir) }.exceptionOrNull()
            ?.let { "${gig.date}  ${gig.id.venueId}  ${gig.title}: ${it.message}" }
    }
    if (failures.isNotEmpty()) {
        println("Could not publish ${failures.size} image(s) - those gigs will render with a broken image:")
        failures.forEach { println("  $it") }
    }

    val unpublished = unpublishedImageFiles(keep, publishedImagesDir.listFiles()?.toList() ?: emptyList())
    unpublished.forEach { it.delete() }
    if (unpublished.isNotEmpty()) println("Unpublished ${unpublished.size} image(s) no gig claims any more (still held in $imageCacheDir)")
}

fun dailyUpdate(today: LocalDate = LocalDate.now(), force: Boolean = false) {
    val log = GigsLog(eventsFile)
    if (!force && log.alreadyRenderedFor(today)) {
        println("Skipping - already updated for $today; pass force to update anyway")
        return
    }

    println("== scrape ==")
    scrapeGigs(force = force)

    println()
    println("== classify ==")
    classifyUnclassifiedGigs()

    println()
    println("== render ==")
    renderGigsHtml(today = today)
}

private fun decodeArgs(rawArgs: Array<String>): List<String> =
    if (rawArgs.size == 1) rawArgs[0].split('\u001F').map { it.replace('\u001E', ' ') } else rawArgs.toList()

fun main(rawArgs: Array<String>) {
    System.setProperty("com.sun.security.enableAIAcaIssuers", "true")

    val args = decodeArgs(rawArgs)

    when (args.firstOrNull()) {
        "scrape" -> {
            val scrapeArgs = args.drop(1)
            scrapeGigs(venueKeys = (scrapeArgs - "force").toSet(), force = scrapeArgs.contains("force"))
        }
        "classify" -> {
            val classifyArgs = args.drop(1)
            if (classifyArgs.firstOrNull() == "status") {
                printClassificationStatus()
            } else if (classifyArgs.firstOrNull() == "override") {
                val url = classifyArgs.getOrNull(1)
                val genre = classifyArgs.getOrNull(2)?.let { arg -> Genre.entries.find { it.name.equals(arg, ignoreCase = true) } }
                if (url == null || genre == null) {
                    println("Usage: classify override <url> <${Genre.entries.joinToString("|") { it.name.lowercase() }}>")
                } else {
                    overrideGigGenre(url, genre)
                }
            } else {
                classifyUnclassifiedGigs(limit = classifyArgs.firstOrNull()?.toIntOrNull())
            }
        }
        "render" -> {
            val renderArgs = args.drop(1)
            val today = renderArgs.firstNotNullOfOrNull { arg -> runCatching { LocalDate.parse(arg) }.getOrNull() }
            renderGigsHtml(today = today ?: LocalDate.now(), force = renderArgs.contains("force"), fullUnresolved = renderArgs.contains("full-unresolved"))
        }
        "daily-update" -> dailyUpdate(force = args.drop(1).contains("force"))
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
        else -> println("Usage: [daily-update [force]|scrape [venue-key...] [force]|classify [limit]|classify status|classify override <url> <genre>|render [yyyy-mm-dd] [force] [full-unresolved]|ingest-poster <imageUrl> <sourceUrl> <venue> [force]]")
    }
}
