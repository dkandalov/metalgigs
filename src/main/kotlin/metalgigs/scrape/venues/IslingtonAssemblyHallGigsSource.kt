package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.Month

val islingtonAssemblyHall = Venue(VenueId("islington-assembly-hall"), "Islington Assembly Hall")

class IslingtonAssemblyHallGigsSource(private val client: HttpHandler, private val year: Int) : GigsSource {
    override val venue = islingtonAssemblyHall

    override fun latestGigs(): List<Gig> {
        val gigs = mutableListOf<Gig>()
        var pageUrl: String? = url
        var pagesFetched = 0
        var currentYear = year
        var previousMonth: Month? = null

        while (pageUrl != null && pagesFetched < maxPages) {
            val page = Jsoup.parse(fetchPage(client, pageUrl), pageUrl)
            pagesFetched++
            gigs += page.select("li.event__item").map { item ->
                // a card prints "Fri 21 Aug" and no year anywhere, so the year is counted forward as
                // the listing crosses back into an earlier month. Gigs are listed in date order and
                // the pages continue that order, so the count carries across pages rather than
                // restarting - the boundary this crosses falls mid-page-4
                val month = monthsByShortName.getValue(item.select(".event__item__date__month").text())
                if (previousMonth != null && month < previousMonth) currentYear++
                previousMonth = month

                val link = item.select("a.event__item__title")
                val gigUrl = link.attr("abs:href")
                // the poster is the background of an anchor the theme lazy-loads, so it has no src
                // at all until its own JavaScript runs - only data-src
                val thumbnailUrl = item.select("a.event__item__background").attr("abs:data-src")

                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(link.text()),
                    LocalDate.of(currentYear, month, item.select(".event__item__date__numeric").text().toInt()),
                    posterUrlFrom(gigUrl, thumbnailSuffixPattern.replace(thumbnailUrl, "$1")),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
            pageUrl = page.select("a.entry__pagination__action[data-js-infinite-next]").attr("abs:href").ifBlank { null }
        }
        return gigs
    }

    private val url = "https://islingtonassemblyhall.co.uk/events/"

    // eighteen to a page, walked by following the listing's own next link. maxPages exists only to
    // bound a pathological site bug; the real stop condition is that link disappearing
    private val maxPages = 10

    // the listing's poster is a 750x450 crop of the upload, and the uploads measured behind those
    // crops ran to 2560x1536. Every one is named "<original>-<W>x<H>-c-center.<ext>"; stripping that
    // suffix recovers the original with no extra request, the same trick as Paper Dress Vintage's
    // -lbox- thumbnails
    private val thumbnailSuffixPattern = Regex("""-\d+x\d+-c-center(\.\w+)$""")

    // Two paragraphs close every listing's copy: the terms and conditions the ticket buyer agrees to,
    // and the £1.50 Venue Levy explained at some length. Together they run to about 470 characters,
    // which on a listing whose promoter wrote nothing is the entire description. Nothing in the
    // markup tells them from the copy - they're sibling paragraphs in the same wysiwyg block, with
    // the leading asterisk they're usually typed with sometimes missing - so they go by their
    // wording, as the age line does at The Underworld and Electric Ballroom.
    private val ticketingBoilerplate = Regex(
        """by purchasing a ticket to this event|subject to a venue levy|^\*?this is an? \d+\+ event""",
        RegexOption.IGNORE_CASE,
    )

    // the "Presented by <promoter>" line is left in: it names who booked the show, which is the one
    // thing some of these listings say beyond the boilerplate
    internal fun eventPageContent(page: Document): String? {
        val description = page.select(".event__description").firstOrNull() ?: return null
        return description.select("p")
            .filterNot { ticketingBoilerplate.containsMatchIn(it.text().trim()) }
            .joinToString(" ") { it.text() }
            .trim()
            .ifBlank { null }
    }
}
