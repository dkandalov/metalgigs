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

// a URL's extension isn't a reliable guide to its actual content type - e.g. Facebook serves some
// images as image/webp via a "dst-webp" transcoding query param even though the path still ends
// in .jpg - so this trusts the server's own Content-Type header first, falling back to the URL's
// extension only if that header is missing
private fun mimeTypeForImageUrl(url: String) =
    when (imageUrlExtension(url).lowercase()) {
        "png" -> MimeType.IMAGE_PNG
        "gif" -> MimeType.IMAGE_GIF
        "webp" -> MimeType.IMAGE_WEBP
        else -> MimeType.IMAGE_JPG
    }

fun fetchImageContent(client: HttpHandler, imageUrl: String): Content.Image {
    val response = client(Request(GET, imageUrl))
    check(response.status.successful) { "Failed to fetch image at $imageUrl: ${response.status}" }
    val mimeType = response.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
        ?.let { MimeType.of(it) } ?: mimeTypeForImageUrl(imageUrl)
    return Content.Image(Resource.Binary(Base64Blob.encode(response.body.stream.readBytes()), mimeType))
}

private val eventsFile = File("events.ndjson")
private val publishedImagesDir = File("images")
private val imageCacheDir = File(".image-cache")

// downloads each gig's image into the local cache, reporting rather than failing on any that don't
// come back - a dead image url shouldn't abort a scrape or a render over the other gigs
private fun cacheImagesReportingFailures(client: HttpHandler, gigs: List<GigEvent>, what: String) {
    val failures = gigs.filter { it.imageUrl.isNotBlank() }.mapNotNull { gig ->
        runCatching { downloadToCache(client, gig.imageUrl, imageCacheDir) }.exceptionOrNull()
            ?.let { "${gig.date()}  ${gig.id.venue}  ${gig.title}: ${it.message}" }
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

    // record a gig whose listed details changed, and also one that's unchanged but has no page text
    // yet - that second case backfills gigs observed before scrape started capturing it, so the log
    // fills in venue by venue instead of needing a separate migration pass
    val missingPageText = gigsMissingPageText(existingEntries)
    val newOrChanged = newOrChangedGigs(existingEntries, gigs).toSet()
    val toObserve = gigs.filter { it in newOrChanged || it.id in missingPageText }

    val observed = toObserve.map { gig ->
        // one dead event page shouldn't cost us the whole scrape - the gig is still worth recording,
        // and the classifier falls back to fetching for a gig with no captured text
        val pageText = runCatching { fetchGigPageText(client, gig) }.getOrNull()
        GigObserved(gig.copy(pageText = pageText), now)
    }
    appendGigLogEntries(eventsFile, observed)

    val withoutText = observed.count { it.gig.pageText == null }
    if (withoutText > 0) println("Could not capture event page text for $withoutText gig(s); they'll be fetched at classification time instead")

    // cache every scraped gig's image now, whatever its genre turns out to be: classification and
    // rendering can be days later, by which time some urls have expired
    cacheImagesReportingFailures(client, gigs, "gig image(s) - those gigs will have no poster")
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

    val classifications = classifyGigs(currentGigs, alreadyClassified(existingEntries), limit) { gig ->
        classifyGigByLLM(client, chat, gig, recordedAt)
    }
    appendGigLogEntries(eventsFile, classifications)

    val affectedKeys = classifications.map { it.id }.toSet()
    val statusByGig = classificationStatusByGig(existingEntries + classifications)
    val newlyMetalGigs = projectMetalGigs(existingEntries + classifications).filter { it.id in affectedKeys }
    // no image handling here any more: scrape has already cached every gig's image, and render
    // publishes the ones its page needs

    printClassificationSummary(classifications, newlyMetalGigs.size, currentGigs, statusByGig)
}

private fun printClassificationSummary(
    classifications: List<GigClassified>,
    newlyMetal: Int,
    currentGigs: List<GigEvent>,
    statusByGig: Map<GigId, ClassificationStatus>,
) {
    println("Classified this run: ${classifications.size} ($newlyMetal Metal, ${classifications.size - newlyMetal} Other)")
    println()

    val statuses = currentGigs.map { statusByGig[it.id] }
    val overallMetal = statuses.count { (it as? ClassificationStatus.Classified)?.genre == Genre.Metal }
    val overallOther = statuses.count { (it as? ClassificationStatus.Classified)?.genre == Genre.Other }

    println("Overall (${currentGigs.size} current gigs):")
    println("  Metal:   $overallMetal")
    println("  Other:   $overallOther")
    println("  Pending: ${currentGigs.size - overallMetal - overallOther}")
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
    // every gig on the poster is asserted Metal, not just the ones whose details changed - the
    // poster is the assertion, so re-ingesting it has to restate the genre for all of them (a gig
    // already observed unchanged would otherwise keep whatever the classifier had made of it)
    val classified = gigs.map { gig ->
        GigClassified(id = gig.id, recordedAt = recordedAt, genre = Genre.Metal, source = ClassificationSource.User)
    }
    appendGigLogEntries(eventsFile, observed + classified)
    // these gigs never go through scrape, so this is their only chance to be cached - and the
    // poster urls here are the most likely to expire
    cacheImagesReportingFailures(client, gigs, "poster image(s)")

    println("${gigs.size} gig(s) extracted from poster (${newOrChanged.size} new/changed), all assumed Metal")
}

// rewrites the log into a new file with every observation carrying its gig's event-page text,
// fetching whatever the log doesn't already hold. Writes alongside rather than in place so the
// original is untouched and the result can be compared before replacing it.
//
// Note this fills superseded observations with the page's text *as it reads today*, not as it read
// when that observation was made - that text is gone. The field means "the gig's page text, best
// known", not "what the page said at recordedAt".
fun migrateLogCapturingPageText(outputFile: File) {
    check(!outputFile.exists()) { "$outputFile already exists - delete it or choose another name, or a re-run would append to it" }
    val client = ClientFilters.FollowRedirects().then(OkHttp())
    val entries = readGigLogEntries(eventsFile)

    // several observations of one gig share a url, so fetch each page once
    val textByUrl = mutableMapOf<String, String?>()
    var fetched = 0
    var failed = 0

    val migrated = entries.mapIndexed { index, entry ->
        if (entry !is GigObserved || entry.gig.pageText != null) return@mapIndexed entry
        val text = textByUrl.getOrPut(entry.gig.id.url) {
            runCatching { fetchGigPageText(client, entry.gig) }
                .onSuccess { fetched++ }
                .onFailure { failed++; println("  could not fetch ${entry.gig.id.venue} - ${entry.gig.title}: ${it.message}") }
                .getOrNull()
        }
        if ((index + 1) % 100 == 0) println("  ${index + 1}/${entries.size} entries...")
        entry.copy(gig = entry.gig.copy(pageText = text))
    }

    appendGigLogEntries(outputFile, migrated)

    val stillMissing = migrated.filterIsInstance<GigObserved>().count { it.gig.pageText == null }
    println("Wrote ${migrated.size} entries to $outputFile")
    println("  pages fetched: $fetched, fetches failed: $failed")
    println("  observations still without text: $stillMissing")
}

fun overrideGigGenre(url: String, genre: Genre) {
    val entries = readGigLogEntries(eventsFile)
    val gig = projectCurrentGigs(entries).find { it.id.url == url }
        ?: error("No current gig found with url $url")

    appendGigLogEntries(eventsFile, listOf(
        GigClassified(id = gig.id, recordedAt = Instant.now(), genre = genre, source = ClassificationSource.User),
    ))
    // no image handling here: the gig's image was cached when it was scraped, and render publishes
    // it if this override put it on the page
}

fun renderGigsHtml(today: LocalDate = LocalDate.now(), force: Boolean = false, fullUnresolved: Boolean = false) {
    val entries = readGigLogEntries(eventsFile)
    val statusByGig = classificationStatusByGig(entries)
    val upcomingGigs = excludeGigsInThePast(projectCurrentGigs(entries), today)
    val unresolved = upcomingGigs.filter { gig -> statusByGig[gig.id] !is ClassificationStatus.Classified }
        .sortedBy { it.date() }

    if (unresolved.isNotEmpty() && !force) {
        val shown = if (fullUnresolved) unresolved else unresolved.take(5)
        val listing = shown.joinToString("\n") { gig ->
            val status = statusByGig[gig.id] ?: ClassificationStatus.Pending
            "  ${gig.date()}  ${gig.id.venue}  ${gig.title}\n  $status\n  ${gig.id.url}"
        }
        val hint = if (shown.size < unresolved.size) " Soonest ${shown.size} (pass full-unresolved to see all)" else ""
        error("${unresolved.size} upcoming gig(s) not yet classified - run classify/override first, or pass force to render anyway.$hint:\n$listing")
    }

    val gigs = excludeGigsInThePast(projectMetalGigs(entries), today)
    publishGigImages(gigs)

    val renderer = HandlebarsTemplates().CachingClasspath()
    File("index.html").writeText(renderer(GigsView(groupGigsByDate(gigs))))
}

// makes images/ hold exactly the images the page references: copies each one out of the download
// cache, and removes any that this page doesn't use. Pruning to just the rendered gigs is safe
// precisely because the cache keeps the bytes - a later or backdated render republishes them with
// a local copy rather than a fetch against a url that may since have expired
private fun publishGigImages(renderedGigs: List<GigEvent>) {
    val client = ClientFilters.FollowRedirects().then(OkHttp())

    val failures = renderedGigs.filter { it.imageUrl.isNotBlank() }.mapNotNull { gig ->
        runCatching { publishGigImage(client, gig, imageCacheDir, publishedImagesDir) }.exceptionOrNull()
            ?.let { "${gig.date()}  ${gig.id.venue}  ${gig.title}: ${it.message}" }
    }
    if (failures.isNotEmpty()) {
        println("Could not publish ${failures.size} image(s) - those gigs will render with a broken image:")
        failures.forEach { println("  $it") }
    }

    val unpublished = unpublishedImageFiles(renderedGigs, publishedImagesDir.listFiles()?.toList() ?: emptyList())
    unpublished.forEach { it.delete() }
    if (unpublished.isNotEmpty()) println("Unpublished ${unpublished.size} image(s) no longer on the page (still held in $imageCacheDir)")
}

// run-main.sh encodes each argument's spaces (0x1e) and joins arguments with 0x1f before handing
// them to Gradle, since --args is re-split on whitespace before we ever see it - undo both here,
// so an argument like a venue name can contain spaces. A direct `gradlew run --args=...`
// invocation (no encoding) still works fine: with no 0x1f present this is a no-op split.
private fun decodeArgs(rawArgs: Array<String>): List<String> =
    if (rawArgs.size == 1) rawArgs[0].split('\u001F').map { it.replace('\u001E', ' ') } else rawArgs.toList()

fun main(rawArgs: Array<String>) {
    val args = decodeArgs(rawArgs)

    when (args.firstOrNull()) {
        "scrape" -> {
            val scrapeArgs = args.drop(1)
            scrapeGigs(venueKeys = (scrapeArgs - "force").toSet(), force = scrapeArgs.contains("force"))
        }
        // classifying a gig yourself is the same operation as letting the classifier do it - both
        // append a GigClassified, differing only in source - so it's a mode of the same command
        "classify" -> {
            val classifyArgs = args.drop(1)
            if (classifyArgs.firstOrNull() == "override") {
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
        "migrate-log" -> {
            val outputFile = args.getOrNull(1)
            if (outputFile == null) {
                println("Usage: migrate-log <outputFile>")
            } else {
                migrateLogCapturingPageText(File(outputFile))
            }
        }
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
        else -> println("Usage: [scrape [venue-key...] [force]|classify [limit]|classify override <url> <genre>|render [yyyy-mm-dd] [force] [full-unresolved]|ingest-poster <imageUrl> <sourceUrl> <venue> [force]|migrate-log <outputFile>]")
    }
}
