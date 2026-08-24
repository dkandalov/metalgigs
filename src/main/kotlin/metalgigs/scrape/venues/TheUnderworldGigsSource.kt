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

    // The venue's standard age policy sits in the same container as the blurb, as a paragraph of
    // its own with nothing in the markup to tell it apart, so it goes by its wording. Seen as both
    // "This is a 14+ event" and "This event is a 14+ event", and on a thin listing it is most of
    // the text.
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


// imgix renders whatever size the url asks for, and The Underworld's listing asks for w=200 - a
// thumbnail sized for its own page, and 8x fewer pixels than the crop behind it (measured: w=200
// gives 200px, dropping it gives the full 1667px, and asking beyond that caps rather than upscales).
// Taking the full crop lets render size it for the card instead of enlarging a thumbnail.
//
// The dice.fm venues draw on this same CDN but link their images with no w at all, which is why
// theirs have always arrived at full size
internal fun imgixUrlWithoutWidth(url: String): String {
    if (!url.contains("imgix.net")) return url
    val base = url.substringBefore('?')
    val params = url.substringAfter('?', "").split("&").filterNot { it.startsWith("w=") || it.isBlank() }
    return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
}
