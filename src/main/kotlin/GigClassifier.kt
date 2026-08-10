import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import java.time.Instant

// starter list; extend as false negatives/positives turn up in practice
private val metalKeywords = listOf(
    "metal", "doom", "sludge", "grind", "grindcore", "death metal", "black metal",
    "thrash", "stoner", "hardcore", "deathcore", "metalcore", "crust", "powerviolence",
)

fun matchKeywords(pageText: String): List<String> =
    metalKeywords.filter { pageText.contains(it, ignoreCase = true) }

fun classifyGig(client: HttpHandler, gig: GigEvent, scrapedAt: Instant): GigClassified {
    val pageText = Jsoup.parse(fetchPage(client, gig.url), gig.url).text()
    return GigClassified(
        venue = gig.venue,
        url = gig.url,
        scrapedAt = scrapedAt,
        matchedKeywords = matchKeywords(pageText),
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
