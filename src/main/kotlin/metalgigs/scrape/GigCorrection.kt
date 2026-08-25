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
    val byUrl = scraped.associateBy { it.id.url }

    return previous.filter { it.date >= from && it.id !in listed }
        .mapNotNull { missing ->
            val lookalikes = scraped.filter { it.date == missing.date && it.looksLike(missing) }
            if (lookalikes.isEmpty()) null
            else when (val says = missingGigSays(missing.id.url)) {
                is MissingGig.MovedTo -> byUrl[says.url]
                MissingGig.Gone -> lookalikes.maxByOrNull { titleSimilarity(it, missing) }
                MissingGig.Live -> null
            }?.let { replacement -> missing.id to replacement.id }
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

internal fun missingGigSays(client: HttpHandler, url: GigUrl): MissingGig {
    val response = client(Request(GET, url.value).header("User-Agent", browserUserAgent))
    return when {
        response.status.redirection -> response.header("location")
            ?.let { MissingGig.MovedTo(GigUrl(Uri.of(url.value).relative(it).toString())) } ?: MissingGig.Live
        response.status == NOT_FOUND || response.status == GONE -> MissingGig.Gone
        else -> MissingGig.Live
    }
}

private const val browserUserAgent =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
