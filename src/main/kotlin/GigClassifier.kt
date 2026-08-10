import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import java.time.Instant

// starter list; extend as false negatives/positives turn up in practice
private val metalKeywords = listOf(
    "metal", "doom", "sludge", "grind", "grindcore", "death metal", "black metal",
    "thrash", "stoner", "hardcore", "deathcore", "metalcore", "crust", "powerviolence",
)

// some venues' event pages embed a sitewide "other events" widget alongside the actual gig
// content; scanning the whole page picks up unrelated shows' titles, so scope to just the gig's
// own content where a venue's markup makes that possible
private val eventPageContentSelectorByVenue = mapOf(
    "The Underworld" to "article.event",
)

fun matchKeywords(pageText: String): List<String> =
    metalKeywords.filter { pageText.contains(it, ignoreCase = true) }

private fun eventPageContentText(pageHtml: String, url: String, venue: String): String {
    val page = Jsoup.parse(pageHtml, url)
    val selector = eventPageContentSelectorByVenue[venue] ?: return page.text()
    val content = page.select(selector)
    check(content.isNotEmpty()) { "Event page content selector \"$selector\" matched nothing for $venue at $url" }
    return content.text()
}

fun classifyGig(client: HttpHandler, gig: GigEvent, scrapedAt: Instant): GigClassified {
    val pageText = eventPageContentText(fetchPage(client, gig.url), gig.url, gig.venue)
    val matchedKeywords = matchKeywords(pageText)
    return GigClassified(
        venue = gig.venue,
        url = gig.url,
        scrapedAt = scrapedAt,
        genre = if (matchedKeywords.isNotEmpty()) Genre.Metal else Genre.Unclassified,
        matchedKeywords = matchedKeywords,
        source = ClassificationSource.Keywords,
    )
}

fun classifyGigs(
    client: HttpHandler,
    gigs: List<GigEvent>,
    alreadyClassified: Set<Pair<String, String>>,
    scrapedAt: Instant,
): List<GigClassified> =
    gigs.filter { (it.venue to it.url) !in alreadyClassified }
        .map { classifyGig(client, it, scrapedAt) }
