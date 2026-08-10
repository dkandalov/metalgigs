import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Instant

// starter list; extend as false negatives/positives turn up in practice
private val metalKeywords = listOf(
    "metal", "doom", "sludge", "grind", "grindcore", "death metal", "black metal",
    "thrash", "stoner", "hardcore", "deathcore", "metalcore", "crust", "powerviolence",
)

// some venues' event pages need special handling to get at the gig's own content:
// - The Underworld embeds a sitewide "other events" widget alongside the actual gig content,
//   so scanning the whole page picks up unrelated shows' titles
// - New Cross Inn (pit.live) renders its description client-side via Alpine.js: the text lives
//   in an x-html attribute, not as element text, so the plain page text never contains it
private val eventPageContentByVenue: Map<String, (Document) -> String?> = mapOf(
    "The Underworld" to { page -> page.select("article.event").let { if (it.isEmpty()) null else it.text() } },
    "New Cross Inn" to { page -> page.select("[x-ref=desc]").firstOrNull()?.attr("x-html") },
)

fun matchKeywords(pageText: String): List<String> =
    metalKeywords.filter { pageText.contains(it, ignoreCase = true) }

private fun eventPageContentText(pageHtml: String, url: String, venue: String): String {
    val page = Jsoup.parse(pageHtml, url)
    val extractContent = eventPageContentByVenue[venue] ?: return page.text()
    return extractContent(page) ?: error("Could not extract event page content for $venue at $url")
}

fun classifyGig(client: HttpHandler, gig: GigEvent, recordedAt: Instant): GigClassified {
    val pageText = eventPageContentText(fetchPage(client, gig.url), gig.url, gig.venue)
    val matchedKeywords = matchKeywords(pageText)
    return GigClassified(
        venue = gig.venue,
        url = gig.url,
        recordedAt = recordedAt,
        genre = if (matchedKeywords.isNotEmpty()) Genre.Metal else Genre.Unclassified,
        matchedKeywords = matchedKeywords,
        source = ClassificationSource.Keywords,
    )
}

fun classifyGigs(
    client: HttpHandler,
    gigs: List<GigEvent>,
    alreadyClassified: Set<Pair<String, String>>,
    recordedAt: Instant,
): List<GigClassified> =
    gigs.filter { (it.venue to it.url) !in alreadyClassified }
        .map { classifyGig(client, it, recordedAt) }
