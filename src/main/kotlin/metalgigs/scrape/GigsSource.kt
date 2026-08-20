package metalgigs.scrape

import metalgigs.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

interface GigsSource {
    val venue: Venue
    fun latestGigs(): List<Gig>
}

// Fails rather than standing in a blank, so no Gig is ever built holding a description its page
// never gave. A page that won't fetch and markup that no longer matches the venue's own selectors
// are both that failure; "" is only ever a page that was read and had nothing to say about its gig.
internal fun fetchDescription(client: HttpHandler, url: String, content: (Document) -> String?): GigDescription =
    descriptionFrom(Jsoup.parse(fetchPage(client, url), url), url, content)

internal fun descriptionFrom(page: Document, url: String, content: (Document) -> String?): GigDescription =
    GigDescription(
        content(page)
            ?: error("No description found on event page $url - the venue's selectors may no longer match it")
    )

// Jsoup returns an empty selection rather than null when nothing matches, and an empty selection's
// text is "", which would pass for an event page that says nothing about its gig.
internal fun Elements.textOrNull(): String? = if (isEmpty()) null else text()

// An unmatched selector and an empty API field both arrive as "" rather than as a failure, and
// PosterUrl's own message has no gig to name when it rejects one.
internal fun posterUrlFrom(gigUrl: String, url: String?): PosterUrl {
    check(!url.isNullOrBlank()) { "No poster for $gigUrl - the venue's listing no longer gives one" }
    return PosterUrl(url)
}

internal val monthsByShortName = Month.entries.associateBy { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
