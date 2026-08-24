package metalgigs.scrape

import metalgigs.*
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
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

// A page that doesn't open is told apart from one whose markup has changed. Without this a 404's own
// body is parsed like any other, matches nothing, and surfaces as "the venue's selectors may no
// longer match it" - which sends whoever reads it looking at selectors that are fine.
internal fun fetchPage(client: HttpHandler, url: String, headers: List<Pair<String, String>> = emptyList()): String {
    val response = client(headers.fold(Request(GET, url)) { request, (name, value) -> request.header(name, value) })
    check(response.status.successful) { "Failed to fetch $url: ${response.status}" }
    return response.bodyString()
}

// Fails rather than standing in a blank, so no Gig is ever built holding a description its page
// never gave. A page that won't fetch and markup that no longer matches the venue's own selectors
// are both that failure; "" is only ever a page that was read and had nothing to say about its gig.
internal fun fetchDescription(client: HttpHandler, url: GigUrl, content: (Document) -> String?): GigDescription =
    descriptionFrom(Jsoup.parse(fetchPage(client, url.value), url.value), url, content)

internal fun descriptionFrom(page: Document, url: GigUrl, content: (Document) -> String?): GigDescription =
    GigDescription(
        content(page)
            ?: error("No description found on event page $url - the venue's selectors may no longer match it")
    )

// For the sources whose venue types the spacing GigTitle refuses, where no better parse avoids it.
// As of the log's migration on 2026-08-21 that is 229, the Signature Brews, Blondies, Helgi's and
// Barfly through Dice, whose API hands a promoter's name back verbatim ("LUN8 "); Alexandra Palace
// and Eventim Apollo, whose own headings carry a narrow no-break space; and Ovo Arena and the AMG
// venues, whose listing APIs do the same. Jsoup's text() already normalises the ASCII whitespace and
// trims, and a JSON name field has nothing else to read, so there is nothing left to parse better.
//
// Deliberately blind to line breaks, tabs and control characters, which no venue types and no API
// returns: those are what a selector matching a card's container rather than its heading brings, so
// they have to reach GigTitle and fail rather than being tidied into a title that looks fine.
internal fun titleFrom(text: String): GigTitle =
    GigTitle(
        text.map { if (it.category == CharCategory.SPACE_SEPARATOR) ' ' else it }
            .joinToString("")
            .replace(Regex(" +"), " ")
            .trim(' '),
    )

// Jsoup returns an empty selection rather than null when nothing matches, and an empty selection's
// text is "", which would pass for an event page that says nothing about its gig.
internal fun Elements.textOrNull(): String? = if (isEmpty()) null else text()

// A listing selector that has widened doesn't come back empty, it comes back with the nav, the
// footer, the "more events" rail - and those links go where this venue's gigs never do. It is the
// one part of a stray link that can't look right: a title, a date and a poster taken off a footer
// are all plausible strings, but a url is a fact about where the thing lives.
//
// Checked by each source as it builds the url, rather than declared to anything, so the answer sits
// beside the selector it guards and fails the venue's scrape the way an unreadable event page
// already does. Several sources honestly span more than one prefix - the AMG venues link to
// Ticketmaster under either scheme and fall back to Gigantic.
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

// An unmatched selector and an empty API field both arrive as "" rather than as a failure, and
// PosterUrl's own message has no gig to name when it rejects one.
internal fun posterUrlFrom(gigUrl: GigUrl, url: String?): PosterUrl {
    check(!url.isNullOrBlank()) { "No poster for $gigUrl - the venue's listing no longer gives one" }
    return PosterUrl(url)
}

internal val monthsByShortName = Month.entries.associateBy { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
