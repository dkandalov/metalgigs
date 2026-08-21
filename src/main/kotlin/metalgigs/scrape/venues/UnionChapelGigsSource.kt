package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

val unionChapel = Venue(VenueId("union-chapel"), "Union Chapel")

class UnionChapelGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = unionChapel
    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            // every card carries its own sortable timestamp for the page's client-side sorting,
            // which beats parsing the human date ("Thu 27 May 2027") printed alongside it
            .select(".item[data-chron]")
            .map { item ->
                // matched on the path, since the other link on a card goes to whichever external
                // ticketing site that gig happens to sell through
                val gigUrl = gigUrlFrom(item.select("a[href*=/whats-on/]").attr("abs:href"), "https://unionchapel.org.uk/whats-on/")
                Gig(
                    GigId(venue.id, gigUrl),
                    // each card prints its title twice, once for the card and once for the hover
                    // panel inside it, so this takes the first rather than both concatenated
                    GigTitle(item.select(".card-title").first()!!.text()),
                    GigDate.parse(item.attr("data-chron").substringBefore(' ')),
                    posterUrlFrom(gigUrl, backgroundImageUrlPattern.find(item.select(".card-image").attr("style"))?.groupValues?.get(1)),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }

    private val url = "https://unionchapel.org.uk/whats-on"

    // e.g. background-image:url("...") - the poster is a css background rather than an img element
    private val backgroundImageUrlPattern = Regex("""url\("([^"]+)"\)""")

    // The gig's copy and the venue's standard sections are flat siblings in one article, told apart
    // only by the heading each section starts with. Measured across six listings, everything from
    // "Book For A Pre-Show Dinner" on is the same ~1,850 characters of cafe, bar and access policy
    // on every page, which is longer than most gigs' own copy. The remaining ticketing instructions
    // are dropped line by line, being boilerplate that sits among the copy rather than after it.
    private val venueSections = Regex(
        """^(book for a pre-show dinner|more information|for accessibility|the venue is seating only)""",
        RegexOption.IGNORE_CASE,
    )
    private val ticketingNotes = Regex(
        """book now button above|set ticket reminder|scroll down for info on reserving""",
        RegexOption.IGNORE_CASE,
    )

    internal fun eventPageContent(page: Document): String? {
        val article = page.select("article.pt-4").firstOrNull() ?: return null
        val ownCopy = article.children()
            .takeWhile { !venueSections.containsMatchIn(it.text().trim()) }
            .filterNot { ticketingNotes.containsMatchIn(it.text()) }
            .joinToString(" ") { it.text() }
        return "$ownCopy ${page.select(".sidebar").text()}".trim().ifBlank { null }
    }
}
