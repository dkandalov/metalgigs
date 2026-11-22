package metalgigs.scrape

import metalgigs.Gig
import metalgigs.GigDate
import metalgigs.GigId
import metalgigs.GigUrl
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Status.Companion.GONE
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Uri
import org.http4k.core.relative
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// Why a moved gig is paired here: docs/adr/0005-a-gig-is-identified-by-the-url-it-lives-at.md
internal sealed interface MissingGig {
    data class MovedTo(val url: GigUrl) : MissingGig

    data object Gone : MissingGig

    data object Live : MissingGig
}

internal fun replacementsIn(
    scraped: List<Gig>,
    previous: List<Gig>,
    from: GigDate,
    missingGigSays: (GigUrl) -> MissingGig,
): List<Pair<GigId, GigId>> {
    val listed = scraped.map { it.id }.toSet()
    val listedTwice = listedTwiceIn(scraped, previous, from)
    val kept = scraped.filterNot { gig -> listedTwice.any { (replaced, _) -> replaced == gig.id } }
    val byUrl = kept.associateBy { it.id.url }

    return listedTwice + previous.filter { it.date >= from && it.id !in listed }
        .mapNotNull { missing ->
            when (val says = missingGigSays(missing.id.url)) {
                is MissingGig.MovedTo -> byUrl[says.url]
                MissingGig.Gone -> kept.filter { it.date == missing.date && it.looksLike(missing) }
                    .maxByOrNull { titleSimilarity(it, missing) }
                MissingGig.Live -> kept.firstOrNull { it.date == missing.date && it.title == missing.title }
            }?.let { replacement -> missing.id to replacement.id }
        }
}

internal fun withoutReplacedCopies(listing: List<Gig>, replacements: Map<GigId, GigId>): List<Gig> {
    val listed = listing.map { it.id }.toSet()
    return listing.filterNot { gig -> generateSequence(replacements[gig.id]) { replacements[it] }.any { it in listed } }
}

private fun listedTwiceIn(scraped: List<Gig>, previous: List<Gig>, from: GigDate): List<Pair<GigId, GigId>> {
    // previous comes in the order the log first saw each gig, the only thing saying which copy is the later.
    val seen = previous.withIndex().associate { (index, gig) -> gig.id to index }

    return scraped.filter { it.date >= from }
        .distinctBy { it.id }
        .groupBy { Triple(it.id.venueId, it.date, it.title) }
        .values.filter { it.size > 1 }
        .flatMap { copies ->
            val latest = copies.maxBy { seen[it.id] ?: Int.MAX_VALUE }
            (copies - latest).map { it.id to latest.id }
        }
}

private fun Gig.looksLike(missing: Gig) =
    titleSimilarity(this, missing) >= LEAST_SIMILAR_TITLE || posterUrl == missing.posterUrl

private fun titleSimilarity(one: Gig, other: Gig): Double {
    val words = wordsIn(one.title.value)
    val otherWords = wordsIn(other.title.value)
    return if (words.isEmpty() || otherWords.isEmpty()) 0.0
    else words.intersect(otherWords).size.toDouble() / words.union(otherWords).size
}

private fun wordsIn(title: String) =
    title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()

private const val LEAST_SIMILAR_TITLE = 0.4

internal fun missingGigSays(client: HttpHandler, url: GigUrl, saysNotFound: (Document) -> Boolean): MissingGig {
    val response = client(Request(GET, url.value).header("User-Agent", browserUserAgent))
    return when {
        response.status.redirection -> response.header("location")
            ?.let { MissingGig.MovedTo(GigUrl(Uri.of(url.value).relative(it).toString())) } ?: MissingGig.Live
        response.status == NOT_FOUND || response.status == GONE -> MissingGig.Gone
        response.status.successful && saysNotFound(Jsoup.parse(response.bodyString(), url.value)) -> MissingGig.Gone
        else -> MissingGig.Live
    }
}

private const val browserUserAgent =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
