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

// the API rejects an image whose base64 encoding is over 10MB, and base64 inflates by about a
// third, so the raw bytes have to stay under roughly this. Checked before sending rather than
// letting the API refuse it, so the failure names the image and costs no paid call
private const val MAX_IMAGE_BYTES = 7_000_000

// a url's extension isn't a reliable guide to its actual content type - e.g. Facebook serves some
// images as image/webp via a "dst-webp" transcoding query param even though the path still ends in
// .jpg - so the server's own Content-Type header wins, and the extension is only the fallback
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

// the classifier judges a poster by its logos, artwork and typography, none of which need the
// venue's original resolution - and Claude charges an image at ceil(w/28) * ceil(h/28) visual
// tokens, so a 1667px Underworld poster costs 3600 and a 4385px dice.fm one hits the model's 4784
// cap, against 784 for the 768px rendition the page itself shows. Reusing the render pipeline's
// conversion keeps that size defined in one place, and reads from the image cache so classifying
// doesn't re-download what scrape already fetched.
//
// Poster ingestion deliberately doesn't do this: it has to read dates and titles off a flyer, and
// that is exactly the small print shrinking would cost it.
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

// downloads each gig's image into the local cache, reporting rather than failing on any that don't
// come back - a dead image url shouldn't abort a scrape or a render over the other gigs
private fun cacheImagesReportingFailures(client: HttpHandler, gigs: List<Gig>, what: String) {
    val failures = gigs.filter { it.imageUrl.isNotBlank() }.mapNotNull { gig ->
        runCatching { downloadToCache(client, gig.imageUrl, imageCacheDir) }.exceptionOrNull()
            ?.let { "${gig.date}  ${gig.id.venue}  ${gig.title}: ${it.message}" }
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
        !force && lastScrapedAt[source.venue]?.isAfter(now.minus(scrapeCooldown)) == true
    }
    skipped.forEach { source -> println("Skipping ${source.venue} - scraped within the last day; pass force to scrape anyway") }

    val gigs = toScrape.flatMap { it.latestGigs() }
    gigs.forEach { println(it) }

    // a gig is recorded only when it's new or its listed details have changed, so an unchanged
    // listing costs nothing and the log stays a record of changes rather than of scrapes
    val toObserve = log.newOrChangedGigs(gigs)

    val observed = toObserve.map { gig ->
        // one dead event page shouldn't cost us the whole scrape - the gig is still worth recording,
        // and the classifier falls back to fetching for a gig with no captured text
        val description = runCatching { fetchGigPageText(client, gig) }.getOrDefault("")
        GigObserved(gig.copy(description = description), now)
    }
    log.append(observed)

    val withoutText = observed.count { it.gig.description.isBlank() }
    if (withoutText > 0) println("Could not capture event page text for $withoutText gig(s); they'll be fetched at classification time instead")

    // cache every scraped gig's image now, whatever its genre turns out to be: classification and
    // rendering can be days later, by which time some urls have expired
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
        failed.forEach { (gig, reason) -> println("  ${gig.date}  ${gig.id.venue}  ${gig.title}: $reason") }
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

// what classify has and hasn't got to, without classifying anything - no API key, no network, no
// cost. Before this the only way to see the backlog was to run render and read the exception it
// throws, which reports upcoming gigs only and refuses to do its actual job while any remain
fun printClassificationStatus(today: LocalDate = LocalDate.now()) {
    val log = GigsLog(eventsFile)
    val statusByGig = log.classificationStatus()
    val currentGigs = log.currentGigs()
    val upcoming = excludeGigsInThePast(currentGigs, today)

    printBreakdown("Overall", currentGigs, statusByGig)
    println()
    // the split that matters for rendering: only upcoming gigs block it, so a large overall
    // backlog of gigs already in the past isn't what's standing in the way
    printBreakdown("Upcoming from $today", upcoming, statusByGig)

    val pendingByVenue = upcoming
        .filter { statusByGig[it.id] !is ClassificationStatus.Classified }
        .groupingBy { it.id.venue }
        .eachCount()

    if (pendingByVenue.isNotEmpty()) {
        println()
        println("Upcoming Pending by venue:")
        pendingByVenue.entries.sortedWith(compareByDescending<Map.Entry<Venue, Int>> { it.value }.thenBy { it.key.name })
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

    val gigs = extractPosterGigs(client, chat, imageUrl, sourceUrl, Venue(venue))
    gigs.forEach { println(it) }

    val newOrChanged = log.newOrChangedGigs(gigs)
    val recordedAt = Instant.now()
    val observed = newOrChanged.map { GigObserved(it, recordedAt) }
    // every gig on the poster is asserted Metal, not just the ones whose details changed - the
    // poster is the assertion, so re-ingesting it has to restate the genre for all of them (a gig
    // already observed unchanged would otherwise keep whatever the classifier had made of it)
    val classified = gigs.map { gig ->
        GigClassified(id = gig.id, recordedAt = recordedAt, genre = Genre.Metal, source = ClassificationSource.User)
    }
    log.append(observed + classified)
    // these gigs never go through scrape, so this is their only chance to be cached - and the
    // poster urls here are the most likely to expire
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
            "  ${gig.date}  ${gig.id.venue}  ${gig.title}\n  $status\n  ${gig.id.url}"
        }
        val hint = if (shown.size < unresolved.size) " Soonest ${shown.size} (pass full-unresolved to see all)" else ""
        error("${unresolved.size} upcoming gig(s) not yet classified - run classify/override first, or pass force to render anyway.$hint:\n$listing")
    }

    val gigs = excludeGigsInThePast(log.metalGigs(), today)
    publishGigImages(gigs)

    val renderer = HandlebarsTemplates().CachingClasspath()
    val html = renderer(GigsView(groupGigsByDate(gigs)))

    // logged only once both files are written, so an entry always means a render that completed
    val renderedAt = Instant.now()
    val archived = archiveRender(html, renderedDir, indexFile, renderedAt)
    log.append(listOf(GigsRendered(archived.name, gigs.size, today, renderedAt)))

    println("Rendered ${gigs.size} gig(s) as of $today to $indexFile, archived as $archived")
}

// makes images/ hold exactly the images the page references: copies each one out of the download
// cache, and removes any that this page doesn't use. Pruning to just the rendered gigs is safe
// precisely because the cache keeps the bytes - a later or backdated render republishes them with
// a local copy rather than a fetch against a url that may since have expired
private fun publishGigImages(renderedGigs: List<Gig>) {
    val client = ClientFilters.FollowRedirects().then(OkHttp())

    val failures = renderedGigs.filter { it.imageUrl.isNotBlank() }.mapNotNull { gig ->
        runCatching { publishGigImage(client, gig, imageCacheDir, publishedImagesDir) }.exceptionOrNull()
            ?.let { "${gig.date}  ${gig.id.venue}  ${gig.title}: ${it.message}" }
    }
    if (failures.isNotEmpty()) {
        println("Could not publish ${failures.size} image(s) - those gigs will render with a broken image:")
        failures.forEach { println("  $it") }
    }

    val unpublished = unpublishedImageFiles(renderedGigs, publishedImagesDir.listFiles()?.toList() ?: emptyList())
    unpublished.forEach { it.delete() }
    if (unpublished.isNotEmpty()) println("Unpublished ${unpublished.size} image(s) no longer on the page (still held in $imageCacheDir)")
}

// the routine daily refresh: everything new, judged, and published, in the only order that works -
// classifying can't judge gigs a scrape hasn't recorded yet, and rendering before classifying would
// leave them off the page. Each step calls the same function its own command does, so behaviour
// can't drift between running this and running the three by hand.
//
// Runs at most once a day, judged by whether the log already holds a render for today - the log is
// the only record that survives between runs, so nothing else needs tracking. force means "do it
// again anyway", and carries through to scrape, whose own per-venue cooldown would otherwise skip
// every venue and leave a forced run with nothing to do but re-render. It deliberately does *not*
// carry through to render, whose force means something else entirely - publishing a page despite
// gigs nobody has classified - which is never something a routine update should decide by itself.
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

// run-main.sh encodes each argument's spaces (0x1e) and joins arguments with 0x1f before handing
// them to Gradle, since --args is re-split on whitespace before we ever see it - undo both here,
// so an argument like a venue name can contain spaces. A direct `gradlew run --args=...`
// invocation (no encoding) still works fine: with no 0x1f present this is a no-op split.
private fun decodeArgs(rawArgs: Array<String>): List<String> =
    if (rawArgs.size == 1) rawArgs[0].split('\u001F').map { it.replace('\u001E', ' ') } else rawArgs.toList()

fun main(rawArgs: Array<String>) {
    // some venues' sites (e.g. The Garage) omit their intermediate CA cert from the TLS handshake;
    // this lets the JVM fetch it automatically instead of failing the connection, same as browsers
    // do. Set here as well as via applicationDefaultJvmArgs because that arg only reaches `gradlew
    // run` and the start scripts - a plain `java -jar` would otherwise fail those venues outright.
    // Setting it this early does take effect: the JVM reads it when it first builds a cert path,
    // which is during the first TLS handshake, well after this line
    System.setProperty("com.sun.security.enableAIAcaIssuers", "true")

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
