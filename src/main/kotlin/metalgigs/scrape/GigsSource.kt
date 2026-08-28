package metalgigs.scrape

import metalgigs.*
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

// Why a source fails loudly: docs/adr/0002-a-source-fails-rather-than-publishing-something-plausible.md
interface GigsSource {
    val venue: Venue
    fun latestGigs(): List<Gig>
}

internal fun fetchPage(client: HttpHandler, url: String, headers: List<Pair<String, String>> = emptyList()): String {
    val response = client(headers.fold(Request(GET, url)) { request, (name, value) -> request.header(name, value) })
    check(response.status.successful) { "Failed to fetch $url: ${response.status}" }
    return response.bodyString()
}

internal fun fetchDescription(client: HttpHandler, url: GigUrl, content: (Document) -> String?): GigDescription =
    descriptionFrom(Jsoup.parse(fetchPage(client, url.value), url.value), url, content)

internal fun descriptionFrom(page: Document, url: GigUrl, content: (Document) -> String?): GigDescription =
    GigDescription(
        content(page)
            ?: error("No description found on event page $url - the venue's selectors may no longer match it")
    )

// Why titles are tidied as read: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
internal fun titleFrom(text: String): GigTitle =
    GigTitle(
        text.map { if (it.category == CharCategory.SPACE_SEPARATOR) ' ' else it }
            .joinToString("")
            .replace(Regex(" +"), " ")
            .trim(' '),
    )

// Jsoup returns an empty selection rather than null when nothing matches, and an empty selection's
// text is "".
internal fun Elements.textOrNull(): String? = if (isEmpty()) null else text()

// The same rule where a venue cuts furniture out of the block it found: whether the selector matched
// is the only thing that says null, and the cut returns text - "" for a block a promoter left empty,
// or one this venue's cut has taken everything out of.
internal fun Document.selectOrNull(selector: String, cut: (Element) -> String): String? =
    selectFirst(selector)?.let(cut)

internal fun gigUrlFrom(url: String, vararg under: String): GigUrl {
    check(under.any { url.startsWith(it) }) {
        "Gig url $url isn't under ${under.joinToString(" or ")} - the listing selector is matching more than this venue's gigs"
    }
    return GigUrl(url)
}

internal fun gigOrSkipped(gigUrl: GigUrl, skippable: Set<GigUrl>, gig: () -> Gig): Gig? =
    try {
        gig()
    } catch (e: Exception) {
        if (gigUrl !in skippable) throw e
        println("Skipping $gigUrl, which is named as ok to lose - ${e.message}")
        null
    }

internal fun posterUrlFrom(gigUrl: GigUrl, url: String?): PosterUrl {
    check(!url.isNullOrBlank()) { "No poster for $gigUrl - the venue's listing no longer gives one" }
    return PosterUrl(url)
}

internal val monthsByShortName = Month.entries.associateBy { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
