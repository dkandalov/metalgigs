package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

val windmillBrixton = Venue(VenueId("windmill-brixton"), "Windmill Brixton")

class WindmillBrixtonGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = windmillBrixton
    override fun latestGigs(): List<Gig> {
        val gigs = mutableListOf<Gig>()
        var pageUrl: String? = url
        var pagesFetched = 0

        while (pageUrl != null && pagesFetched < maxPages) {
            val page = Jsoup.parse(fetchPage(client, pageUrl), pageUrl)
            pagesFetched++
            gigs += page.select("a.EventLink").map { item ->
                val gigUrl = gigUrlFrom(item.attr("abs:href"), "https://www.windmillbrixton.co.uk/events/")

                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(item.select(".title.name").text()),
                    GigDate.parse(slugDatePattern.find(gigUrl)!!.groupValues[1]),
                    // the second img in the card is a small backup the theme swaps in if the first
                    // fails to load, so this takes the first rather than both
                    posterUrlFrom(gigUrl, item.select(".Image-wrap img").first()?.attr("abs:src")?.let { originalSizeImageUrl(it) }),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
            // on the last page the next link is still there, pointing at "#" and marked disabled -
            // following that would re-fetch the same page for as long as maxPages allowed
            pageUrl = page.select(".Pagination-next a:not([disabled])").attr("abs:href").ifBlank { null }
        }
        return gigs
    }

    private val url = "https://www.windmillbrixton.co.uk/listings/categories/all/"

    // a card prints "Sun, Aug 16" with no year anywhere near it; the only year on the listing is the
    // one Music Glue puts at the front of every event's own path, e.g. /events/2026-08-16-magnolia-...
    private val slugDatePattern = Regex("""/events/(\d{4}-\d{2}-\d{2})-""")

    // twelve to a page, walked by following the listing's own next link. maxPages exists only to
    // bound a pathological site bug; the real stop condition is that link being disabled
    private val maxPages = 10

    // the whole page's text is the venue's nav, its mailing-list form and its socials wrapped around
    // ticketing furniture - price, entry requirements, a per-ticket-type buy panel - none of it about
    // the gig. This is the one block the promoter writes, and every listing read had one.
    internal fun eventPageContent(page: Document) = page.select(".EventDetailDescription").textOrNull()

    // the listing's thumbnail asks the Music Glue image CDN to fit the image into 600px, which is
    // below the 768px render target; dropping the resize parameters returns the original upload
    // (measured: 600x750 with them against 1080x1350 without) with no extra request, the same
    // reasoning as dropping The Underworld's imgix w= parameter
    private fun originalSizeImageUrl(url: String): String {
        val base = url.substringBefore('?')
        val params = url.substringAfter('?', "").split("&")
            .filterNot { it.startsWith("mode=") || it.startsWith("width=") || it.isBlank() }
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }
}
