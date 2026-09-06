package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

val roundhouse = Venue(VenueId("roundhouse"), "Roundhouse")

class RoundhouseGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = roundhouse

    override fun latestGigs(): List<Gig> {
        val gigs = mutableListOf<Gig>()
        var pageUrl: String? = url
        var pagesFetched = 0

        while (pageUrl != null) {
            val page = Jsoup.parse(fetchPage(client, pageUrl), pageUrl)
            pagesFetched++
            gigs += page.select(".event-card").map { item ->
                val link = item.select(".event-card__link")
                val (day, monthName, year) = datePattern.find(item.select(".event-card__date").text())!!.destructured

                val gigUrl = gigUrlFrom(link.attr("abs:href"), "https://www.roundhouse.org.uk/whats-on/")
                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(item.select(".event-card__title").text()),
                    GigDate(2000 + year.toInt(), monthsByShortName.getValue(monthName), day.toInt()),
                    posterUrlFrom(gigUrl, item.select(".event-card__image img").attr("abs:src")),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
            pageUrl = nextPageUrl(page)
            // Failing rather than stopping, unlike the other four listings walked this way: what
            // this source did for its first month was return the nine cards of page one as the whole
            // listing, and a bound that stops quietly is the same answer arrived at differently.
            check(pageUrl == null || pagesFetched < maxPages) {
                "$venue still offers a next page after $maxPages of them - either its listing has outgrown " +
                    "this bound or its pagination now loops, and both would read as a listing that just ended"
            }
        }
        return gigs
    }

    // The next link's path is followed but its query is not: the site serves this page from a cache
    // that sometimes has an ad campaign's fbclid and utm_* baked into every pagination href, so one
    // walk was recorded carrying two different fbclids. They change nothing the server reads, and
    // asking with the query this source already asks with keeps a walk the same walk twice running.
    private fun nextPageUrl(page: Document): String? =
        page.select("a.next.page-numbers").attr("abs:href").ifBlank { null }
            ?.let { "${it.substringBefore('?')}?${url.substringAfter('?')}" }

    // Nine to a page, walked by following the listing's own next link rather than guessing at
    // /events/page/N/ - which the site ignores anyway, `?paged=` and `?page=` both answering page
    // one while only the /page/N/ path moves.
    // Why a listing is paged by the link the page follows: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
    private val maxPages = 20

    // type=event drops the venue's youth-programme courses, which share 145 words of standard access
    // and bursary copy embedded in each course's own text block - unscopeable, and enough to read as
    // site-wide boilerplate.
    private val url = "https://www.roundhouse.org.uk/whats-on/?type=event"

    // e.g. "Wed 12 Aug 26" or a multi-day range "Wed 12 Aug 26–Fri 14 Aug 26"; only the start date is used
    private val datePattern = Regex("""(\d{1,2}) (\w{3}) (\d{2})""")

    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    internal fun eventPageContent(page: Document): String? {
        val content = page.select(".event-hero__heading-wrapper, section.event-about")
        // Both sit inside .event-about rather than beside it.
        content.select(".layout-block--related-events-list, .layout-block--event-listing-card").remove()
        return content.textOrNull()
    }
}
