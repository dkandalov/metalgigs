package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

val theUnderworld = Venue(VenueId("underworld"), "The Underworld")

class TheUnderworldGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = theUnderworld
    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url, listOf("User-Agent" to browserUserAgent)), url)
            .select("#gigs article.list")
            .map { item ->
                val gigUrl = gigUrlFrom(item.select(".list-header-title a").attr("abs:href"), "https://www.theunderworldcamden.co.uk/event/")
                val listedAs = GigTitle(item.select(".list-header-title").text())
                val eventPage = Jsoup.parse(fetchPage(client, gigUrl.value), gigUrl.value)
                Gig(
                    GigId(venue.id, gigUrl),
                    billedTitle(eventPage, listedAs) ?: listedAs,
                    GigDate.parse(item.select("time").first()!!.attr("datetime")),
                    posterUrlFrom(gigUrl, imgixUrlWithoutWidth(item.select(".list-image img").attr("abs:src"))),
                    descriptionFrom(eventPage, gigUrl, ::eventPageContent),
                )
            }

    private val url = "https://www.theunderworldcamden.co.uk/search-events/"

    // the site blocks requests without a browser-like User-Agent
    private val browserUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Seen as both "This is a 14+ event" and "This event is a 14+ event".
    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    private val agePolicy = Regex("""^this (is|event is) an? \d+\+ event""", RegexOption.IGNORE_CASE)

    // An event page carries the sitewide "other events" widget alongside the gig's own content, so
    // the whole page's text picks up unrelated shows' titles.
    internal fun eventPageContent(page: Document): String? {
        val article = page.select("article.event").firstOrNull()?.clone() ?: return null
        article.select("footer").remove()
        article.select("p")
            .filter { agePolicy.containsMatchIn(it.text().trim()) || it.text().contains("carry PHOTO ID") }
            .forEach { it.remove() }
        return article.text().ifBlank { null }
    }

    // A running order is the whole bill as the lineup types it - cased, headliner last - where the
    // listing heading gives the headliner alone and in capitals. Only taken when that last act is
    // what the gig is listed as: a festival or a club night is named for something no act is.
    internal fun billedTitle(page: Document, listedAs: GigTitle): GigTitle? {
        val bill = lineupActs(page).asReversed()
        if (bill.size < 2 || !bill.first().equals(listedAs.value, ignoreCase = true)) return null
        return titleFrom(bill.take(actsInTitle).joinToString(" / "))
    }

    // Doors and curfew carry a clock time where every act carries an en dash, so the time is what
    // tells them apart. Not position: Cosmic Void Festival's page ends on its last act, with no
    // curfew row under it.
    private fun lineupActs(page: Document): List<String> =
        page.select("ul.event-details-lineup li")
            .filterNot { it.select("time").text().any(Char::isDigit) }
            .map { it.select("p").text() }
            .filter { it.isNotBlank() }

    // The headliner and three supports. A bill runs longer - Cosmic Void Festival's is 39 acts - and
    // the rest of one is a programme rather than a title.
    private val actsInTitle = 4
}


// Why the width parameter is dropped: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
internal fun imgixUrlWithoutWidth(url: String): String {
    if (!url.contains("imgix.net")) return url
    val base = url.substringBefore('?')
    val params = url.substringAfter('?', "").split("&").filterNot { it.startsWith("w=") || it.isBlank() }
    return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
}
