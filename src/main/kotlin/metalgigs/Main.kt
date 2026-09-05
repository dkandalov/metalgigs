package metalgigs

import dev.forkhandles.result4k.failureOrNull
import dev.forkhandles.result4k.peek
import dev.forkhandles.result4k.peekFailure
import dev.forkhandles.result4k.resultFrom
import dev.forkhandles.result4k.valueOrNull
import metalgigs.classify.*
import metalgigs.render.*
import metalgigs.scrape.*
import metalgigs.scrape.venues.*
import metalgigs.validate.*
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.AnthropicAI
import org.http4k.ai.llm.model.Content
import org.http4k.ai.llm.model.Resource
import org.http4k.ai.model.ApiKey
import org.http4k.ai.model.SystemPrompt
import org.http4k.connect.model.Base64Blob
import org.http4k.connect.model.MimeType
import org.http4k.core.HttpHandler
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.template.HandlebarsTemplates
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

fun main(rawArgs: Array<String>) {
    System.setProperty("com.sun.security.enableAIAcaIssuers", "true")

    val args = decodeArgs(rawArgs)

    when (args.firstOrNull()) {
        "scrape" -> {
            val scrapeArgs = args.drop(1)
            scrapeGigs(venueIds = (scrapeArgs - "force").map { VenueId(it) }.toSet(), force = scrapeArgs.contains("force"))
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
                // a limit is the numeric argument and a venue id is any other, so the two are told
                // apart by shape rather than by position, as force already is
                val named = classifyArgs - "force"
                classifyUnclassifiedGigs(
                    venueIds = named.filter { it.toIntOrNull() == null }.map { VenueId(it) }.toSet(),
                    limit = named.firstNotNullOfOrNull { it.toIntOrNull() },
                    force = classifyArgs.contains("force"),
                )
            }
        }
        "render" -> {
            val renderArgs = args.drop(1)
            val today = renderArgs.firstNotNullOfOrNull { arg -> resultFrom { LocalDate.parse(arg) }.valueOrNull() }
            renderGigsHtml(today = today ?: LocalDate.now(), force = renderArgs.contains("force"), fullUnresolved = renderArgs.contains("full-unresolved"))
        }
        "daily-update" -> dailyUpdate(force = args.drop(1).contains("force"))
        "compact" -> compactLog()
        else -> println("Usage: [daily-update [force]|scrape [venue-id...] [force]|classify [venue-id...] [limit] [force]|classify status|classify override <url> <genre>|render [yyyy-mm-dd] [force] [full-unresolved]|compact]")
    }
}

private fun decodeArgs(rawArgs: Array<String>): List<String> =
    if (rawArgs.size == 1) rawArgs[0].split('\u001F').map { it.replace('\u001E', ' ') } else rawArgs.toList()

private fun dailyUpdate(today: LocalDate = LocalDate.now(), force: Boolean = false) {
    val log = GigsLog(eventsFile)
    if (!force && log.alreadyRenderedFor(today)) {
        println("Skipping - already updated for $today; pass force to update anyway")
        return
    }

    println("== scrape ==")
    val scraped = scrapeGigs(force = force)

    println()
    println("== classify ==")
    val classification = classifyUnclassifiedGigs()

    // Before render rather than after it, because render is the step that refuses to run - a table
    // of what the two steps before it did is worth having on the run that ends there.
    println()
    println("== venues ==")
    val venueRuns = withClassifications(
        scraped,
        classified = classification.classified.groupingBy { it.id.venueId }.eachCount(),
        failedToClassify = classification.failed.groupingBy { (gig, _) -> gig.id.venueId }.eachCount(),
    )
    venueRunTable(venueRuns).forEach { println(it) }

    println()
    println("== render ==")
    renderGigsHtml(today = today)
}

private fun scrapeGigs(venueIds: Set<VenueId> = emptySet(), force: Boolean = false): List<VenueRun> {
    // Most sources want a redirect followed - Paper Dress Vintage links its gigs by shortlink - but
    // the Dice ones read a gig's own url off the redirect itself, so they take the client that hands
    // one back rather than the one that follows it.
    val unredirectedClient = unredirectedHttpClient()
    val client = ClientFilters.FollowRedirects().then(unredirectedClient)
    val sourcesByVenueId = allSources(client, unredirectedClient).associateBy { it.venue.id }

    val unknownIds = venueIds - sourcesByVenueId.keys
    check(unknownIds.isEmpty()) { "Unknown venue id(s): $unknownIds. Known venue ids: ${sourcesByVenueId.keys}" }
    val sources = if (venueIds.isEmpty()) sourcesByVenueId.values.toList() else venueIds.map { sourcesByVenueId.getValue(it) }

    val log = GigsLog(eventsFile)
    val lastScrapedAt = log.lastScrapedAt()
    val now = Instant.now()

    val (skipped, toScrape) = sources.partition { source ->
        !force && lastScrapedAt[source.venue.id]?.isAfter(now.minus(scrapeCooldown)) == true
    }
    skipped.forEach { source -> println("Skipping ${source.venue} - scraped within the last day; pass force to scrape anyway") }

    // A gig whose event page won't read fails its source outright rather than being built without a
    // description, so one dead page costs that venue its whole listing for this run. Caught per
    // venue so it doesn't cost the others theirs too; nothing is logged for it, so the cooldown sees
    // it as unscraped and comes back within the day.
    //
    // A venue that failed is left out of what's validated rather than entered against an empty list,
    // so it isn't reported a second time as having listed nothing.
    val attempts = toScrape.mapIndexed { index, source ->
        println("Scraping ${source.venue} (${index + 1}/${toScrape.size})...")
        val startedAt = Instant.now()
        val gigs = resultFrom { source.latestGigs() }
        val took = Duration.between(startedAt, Instant.now())
        gigs.peek { println("  ${it.size} gig(s) listed in ${elapsedText(took)}") }
            .peekFailure { println("  Could not scrape ${source.venue} after ${elapsedText(took)}, so none of its gigs are logged this run: ${it.message}") }
        ScrapeAttempt(source.venue.id, gigs, took)
    }
    val scrapedByVenue = attempts.mapNotNull { attempt -> attempt.gigs.valueOrNull()?.let { attempt.venueId to it } }.toMap()
    val gigs = scrapedByVenue.values.flatten()

    val toObserve = log.newOrChangedGigs(gigs)

    val currentGigs = log.currentGigs()
    val validation = validateGigs(scrapedByVenue, GigDate(LocalDate.now()), currentGigs)
    validation.reports.forEach { (heading, problems) ->
        println(heading)
        problems.forEach { println("  ${venue(it.venueId)} (${it.detail}) ${it.where()}".trimEnd()) }
    }

    // Only this run can see a gig go missing: by the next one the old url is no longer current and
    // there is nothing left to compare, so what the listing says is settled here rather than read
    // back later. Skipped for a venue whose gigs are withheld - a listing that failed a check is not
    // one to conclude anything from - and asked only of gigs still to come, a night already played
    // being one no page prints.
    //
    // Costs one request per gig the venue has stopped listing, which is also what stops it repeating:
    // a gig that has been replaced leaves currentGigs, so the next run doesn't ask about it again.
    val withheldVenues = validation.withheld.map { it.id.venueId }.toSet()
    val replacements = scrapedByVenue.filterKeys { it !in withheldVenues }
        .flatMap { (venueId, listing) ->
            replacementsIn(listing, currentGigs.filter { it.id.venueId == venueId }, GigDate(LocalDate.now())) {
                missingGigSays(unredirectedClient, it)
            }
        }
        .map { (replaced, by) -> GigReplaced(replaced, by, now) }
    replacements.forEach { println("  ${venue(it.replaced.venueId)} relisted ${it.replaced.url} as ${it.by.url}") }

    val observed = toObserve.filterNot { it in validation.withheld }.map { gig -> GigObserved(gig, now) }
    log.append(observed + replacements)
    val withheld = toObserve.size - observed.size
    println("Logged ${observed.size} new or changed gig(s) of ${gigs.size} scraped" +
        if (withheld > 0) ", withholding $withheld that failed validation" else "")

    cacheImagesReportingFailures(client, gigs, "gig image(s) - those gigs will have no poster")

    val problemsByVenue = (
        attempts.mapNotNull { attempt -> attempt.gigs.failureOrNull()?.let { attempt.venueId to (it.message ?: it.toString()) } } +
            validation.reports.flatMap { it.problems }.map { it.venueId to it.detail } +
            withheldVenues.map { it to "listing withheld" } +
            replacements.groupingBy { it.replaced.venueId }.eachCount().map { (venueId, count) -> venueId to "$count gig(s) relisted" }
        ).groupBy({ it.first }, { it.second })

    return venueRunsFrom(
        skipped.map { it.venue.id },
        attempts,
        newOrChanged = toObserve,
        alreadyLogged = currentGigs.map { it.id }.toSet(),
        problems = problemsByVenue,
    )
}

// Scoped by venue and forced the way scrape is, and for the same reason: a source that has changed
// what it says about its gigs leaves that venue's verdicts standing on text it no longer serves,
// and nothing else revisits a gig once it has been judged. Forcing re-asks about gigs that already
// have an LLM verdict, at a paid call each, which is why it's never what a routine run does.
//
// Unlike scrape, the venues here are every venue in the log rather than every venue with a source:
// a venue nothing scrapes still has its gigs classified like any other.
private fun classifyUnclassifiedGigs(venueIds: Set<VenueId> = emptySet(), limit: Int? = null, force: Boolean = false): ClassificationRun {
    val client = httpClient()
    val log = GigsLog(eventsFile)
    val currentGigs = log.currentGigs()
    val recordedAt = Instant.now()

    val unknownIds = venueIds - allVenues.map { it.id }.toSet()
    check(unknownIds.isEmpty()) { "Unknown venue id(s): $unknownIds. Known venue ids: ${allVenues.map { it.id }}" }
    val toConsider = if (venueIds.isEmpty()) currentGigs else currentGigs.filter { it.id.venueId in venueIds }

    val apiKey = ApiKey.of(
        System.getenv("ANTHROPIC_API_KEY")
            ?: error("ANTHROPIC_API_KEY environment variable is required for LLM classification")
    )
    val chat = Chat.AnthropicAI(apiKey = apiKey, http = httpClient(llmCallTimeout), systemPrompt = SystemPrompt.of(llmClassifierSystemPrompt))

    val settled = if (force) log.overriddenByUser() else log.alreadyClassified()
    val classifier = WithAlwaysMetalVenues(LlmGigClassifier(client, chat))
    val run = classifyGigs(toConsider, settled, recordedAt, limit, classifier)
    val classifications = run.classified
    log.append(classifications)

    val affectedKeys = classifications.map { it.id }.toSet()
    val statusByGig = log.classificationStatus()
    val newlyMetalGigs = log.metalGigs().filter { it.id in affectedKeys }

    printClassificationSummary(classifications, newlyMetalGigs.size, currentGigs, statusByGig, run.failed)

    return run
}

private fun renderGigsHtml(today: LocalDate = LocalDate.now(), force: Boolean = false, fullUnresolved: Boolean = false) {
    val log = GigsLog(eventsFile)
    val statusByGig = log.classificationStatus()
    // the same window the page uses, so an unclassified gig too far out to be rendered doesn't
    // hold up a render it would never have appeared in
    val upcomingGigs = gigsOnThePage(log.currentGigs(), today)
    val unresolved = upcomingGigs.filter { gig -> statusByGig[gig.id] !is ClassificationStatus.Classified }
        .sortedBy { it.date }

    if (unresolved.isNotEmpty() && !force) {
        val shown = if (fullUnresolved) unresolved else unresolved.take(5)
        val listing = shown.joinToString("\n") { gig ->
            val status = statusByGig[gig.id] ?: ClassificationStatus.Pending
            "  ${gig.date}  ${venue(gig.id.venueId)}  ${gig.title}\n  $status\n  ${gig.id.url}"
        }
        val hint = if (shown.size < unresolved.size) " Soonest ${shown.size} (pass full-unresolved to see all)" else ""
        error("${unresolved.size} upcoming gig(s) not yet classified - run classify/override first, or pass force to render anyway.$hint:\n$listing")
    }

    val metalGigs = log.metalGigs()
    val gigs = gigsOnThePage(metalGigs, today)
    publishGigImages(gigs, keep = metalGigs)

    val renderer = HandlebarsTemplates().CachingClasspath()
    val html = renderer(GigsView(groupGigsByDate(gigs)))

    val renderedAt = Instant.now()
    // Read before archiveRender overwrites it, since the sitemap dates the page by whether this
    // render differs from the published one.
    val publishedHtml = indexFile.takeIf { it.exists() }?.readText()
    val archived = archiveRender(html, renderedDir, indexFile, renderedAt)
    val sitemapUpdated = updateSitemap(sitemapFile, publishedHtml, html, today)
    log.append(listOf(GigsRendered(archived.name, gigs.size, today, renderedAt)))

    println("Rendered ${gigs.size} gig(s) as of $today to $indexFile, archived as $archived")
    println(if (sitemapUpdated) "  $sitemapFile now says lastmod $today" else "  page unchanged, so $sitemapFile keeps its lastmod")
}

private fun printClassificationStatus(today: LocalDate = LocalDate.now()) {
    val log = GigsLog(eventsFile)
    val statusByGig = log.classificationStatus()
    val currentGigs = log.currentGigs()
    val upcoming = excludeGigsInThePast(currentGigs, today)

    printBreakdown("Overall", currentGigs, statusByGig)
    println()
    printBreakdown("Upcoming from $today", upcoming, statusByGig)

    val pendingByVenue = upcoming
        .filter { statusByGig[it.id] !is ClassificationStatus.Classified }
        .groupingBy { venue(it.id.venueId).name }
        .eachCount()

    if (pendingByVenue.isNotEmpty()) {
        println()
        println("Upcoming Pending by venue:")
        pendingByVenue.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .forEach { (venue, count) -> println("  ${count.toString().padStart(4)}  $venue") }
    }
}

private fun overrideGigGenre(url: String, genre: Genre) {
    val log = GigsLog(eventsFile)
    val gig = log.currentGigs().find { it.id.url == GigUrl(url) }
        ?: error("No current gig found with url $url")

    log.append(listOf(
        GigClassified(gig.id, Instant.now(), genre, ClassificationSource.User),
    ))
}

// Why these checks: docs/adr/0001-the-log-is-append-only.md
private fun compactLog() {
    val log = GigsLog(eventsFile)
    val compacted = log.compact()

    val compactedFile = File("events-compacted.ndjson").apply { delete() }
    try {
        GigsLog(compactedFile).append(compacted.entries)
        val compactedLog = GigsLog(compactedFile)

        check(compactedLog.currentGigs().toSet() == log.currentGigs().toSet()) { "Compacted log holds different gigs" }
        check(compactedLog.classificationStatus() == log.classificationStatus()) { "Compacted log judges gigs differently" }
        check(compactedLog.metalGigs().toSet() == log.metalGigs().toSet()) { "Compacted log renders different gigs" }
        check(compactedLog.alreadyClassified() == log.alreadyClassified()) { "Compacted log has classified different gigs" }
        check(compactedLog.lastScrapedAt() == log.lastScrapedAt()) { "Compacted log dates the last scrape differently" }
        check(compactedLog.firstSeenAt() == log.firstSeenAt()) { "Compacted log dates gigs' first sighting differently" }

        val sizeBefore = eventsFile.length()
        compactedFile.copyTo(eventsFile, overwrite = true)

        val observed = compacted.entries.count { it is GigObserved }
        val classified = compacted.entries.count { it is GigClassified }
        val dropped = compacted.observationsDropped + compacted.classificationsDropped

        println("Compacted ${compacted.entries.size + dropped} log entries to ${compacted.entries.size}:")
        println("  observed:   ${observed + compacted.observationsDropped} -> $observed")
        println("  classified: ${classified + compacted.classificationsDropped} -> $classified")
        println("  rendered:   ${compacted.entries.count { it is GigsRendered }} (kept)")
        println("  ${sizeBefore / 1024}KB -> ${eventsFile.length() / 1024}KB")
    } finally {
        compactedFile.delete()
    }
}

private fun allSources(client: HttpHandler, unredirectedClient: HttpHandler): List<GigsSource> = listOf(
    CartAndHorsesGigsSource(client, LocalDate.now().year),
    NewCrossInnGigsSource(client),
    TheBlackHeartGigsSource(client),
    TheUnderworldGigsSource(client),
    DomeLondonGigsSource(client),
    FiddlersElbowGigsSource(client),
    BlondiesBreweryTaproomGigsSource(unredirectedClient),
    BlondiesBarGigsSource(unredirectedClient),
    HelgisGigsSource(unredirectedClient),
    ElectricBallroomGigsSource(client, LocalDate.now().year),
    ElectricBrixtonGigsSource(client),
    DingwallsGigsSource(client),
    TheGarageGigsSource(client),
    RoundhouseGigsSource(client),
    SignatureBrewBlackhorseRoadGigsSource(unredirectedClient),
    SignatureBrewHaggerstonGigsSource(unredirectedClient),
    O2ForumKentishTownGigsSource(client),
    O2AcademyBrixtonGigsSource(client),
    TheGraceGigsSource(client),
    O2AcademyIslingtonGigsSource(client),
    O2ShepherdsBushEmpireGigsSource(client),
    UnionChapelGigsSource(client),
    ScalaGigsSource(client),
    TwoTwoNineGigsSource(unredirectedClient),
    AlexandraPalaceGigsSource(client),
    PaperDressVintageGigsSource(client),
    WindmillBrixtonGigsSource(client),
    IslingtonAssemblyHallGigsSource(client, LocalDate.now().year),
    BarflyGigsSource(unredirectedClient),
    EventimApolloGigsSource(client),
    OvoArenaGigsSource(client),
    IndigoAtTheO2GigsSource(client),
    TheO2ArenaGigsSource(client),
    BushHallGigsSource(client),
    DevGigSource(client, Chat.Ollama(httpClient(ollamaCallTimeout), SystemPrompt.of(flyerExtractionSystemPrompt))),
).map(::WithTidiedTitles)

private val scrapeCooldown: Duration = Duration.ofDays(1)

private fun GigsProblem.where() = when {
    gigs.isEmpty() -> ""
    gigs.size == 1 -> gigs.first().id.url
    else -> "${gigs.size} gigs, e.g. ${gigs.first().id.url}"
}

private fun cacheImagesReportingFailures(client: HttpHandler, gigs: List<Gig>, what: String) {
    val failures = gigs.mapNotNull { gig ->
        resultFrom { downloadToCache(client, gig.posterUrl, imageCacheDir) }.failureOrNull()
            ?.let { "${gig.date}  ${venue(gig.id.venueId)}  ${gig.title}: ${it.message}" }
    }
    if (failures.isNotEmpty()) {
        println("Could not download ${failures.size} $what:")
        failures.forEach { println("  $it") }
    }
}

// keep is every metal gig, not just the rendered ones, so a gig off either end of the page's window
// - already played, or further than a year out - doesn't take its image with it. Only an image no
// gig claims any more is removed, so one coming into range needs no refetch.
private fun publishGigImages(renderedGigs: List<Gig>, keep: List<Gig>) {
    val client = httpClient()

    val failures = renderedGigs.mapNotNull { gig ->
        resultFrom { publishGigImage(client, gig, imageCacheDir, publishedImagesDir) }.failureOrNull()
            ?.let { "${gig.date}  ${venue(gig.id.venueId)}  ${gig.title}: ${it.message}" }
    }
    if (failures.isNotEmpty()) {
        println("Could not publish ${failures.size} image(s) - those gigs will render with a broken image:")
        failures.forEach { println("  $it") }
    }

    val unpublished = unpublishedImageFiles(keep, publishedImagesDir.listFiles()?.toList() ?: emptyList())
    unpublished.forEach { it.delete() }
    if (unpublished.isNotEmpty()) println("Unpublished ${unpublished.size} image(s) no gig claims any more (still held in $imageCacheDir)")
}

private fun printClassificationSummary(
    classifications: List<GigClassified>,
    newlyMetal: Int,
    currentGigs: List<Gig>,
    statusByGig: Map<GigId, ClassificationStatus>,
    failed: List<Pair<Gig, String>>,
) {
    println("Classified this run: ${classifications.size} ($newlyMetal Metal, ${classifications.size - newlyMetal} Other)")
    classificationCostReport(classifications).forEach { println("  $it") }
    if (failed.isNotEmpty()) {
        println("Could not classify ${failed.size} gig(s) - they stay Pending:")
        failed.forEach { (gig, reason) -> println("  ${gig.date}  ${venue(gig.id.venueId)}  ${gig.title}: $reason") }
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

fun fetchPosterForClassifying(client: HttpHandler, imageUrl: PosterUrl): Content.Image {
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
private val sitemapFile = File("sitemap.xml")
private val renderedDir = File(".rendered")
