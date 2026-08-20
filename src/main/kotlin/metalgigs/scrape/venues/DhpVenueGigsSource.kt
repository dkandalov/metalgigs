package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// shared by DHP Family's venue sites, which all use the same card markup
internal class DhpVenueGigsSource(private val client: HttpHandler, private val url: String, override val venue: Venue) : GigsSource {
    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select(".card.card--full")
            .map { item ->
                val (day, monthName, year) = datePattern.find(item.select(".card__strip-heading").text())!!.destructured
                val img = item.select(".card__grid-media img")
                val heading = item.select(".card__heading")
                // a sold-out gig's heading isn't a link at all - its only link is the "Gig Sold Out"
                // notification, which points at the same gig page
                val gigUrl = heading.attr("abs:href").ifBlank { item.select(".card__notification a").attr("abs:href") }
                val cardImage = img.attr("abs:data-lazy-src").ifBlank { img.attr("abs:src") }
                val eventPage = Jsoup.parse(fetchPage(client, gigUrl), gigUrl)

                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(heading.text()),
                    GigDate(2000 + year.toInt(), monthsByShortName.getValue(monthName), day.toInt()),
                    PosterUrl(cardImage.ifBlank { articleImage(eventPage, gigUrl) }),
                    descriptionFrom(eventPage, gigUrl, ::eventPageContent),
                )
            }

    // e.g. "Fri.14.Aug.26" - two-digit year; some gigs have no image at all, just placeholder text
    private val datePattern = Regex("""\w{3}\.(\d{2})\.(\w{3})\.(\d{2})""")

    // The outer wrapper div carries ".single-article" too, so selecting that doubles every word of
    // the text; only the inner section has the list-contains class. Within it, everything but
    // .single-article__content is ticketing furniture - a title bar, a meta bar of date, time and
    // price, and the same three repeated as a Date/Doors Open/On Sale list - and the copy itself
    // closes with a "For more events" link on every listing. What survives is a bio where the
    // promoter wrote one and a one-line "Tickets are now available for X" template where they
    // didn't, which is all these pages say about those gigs.
    private val moreEventsCta = Regex("""for more events|check out what.s on here""", RegexOption.IGNORE_CASE)

    internal fun eventPageContent(page: Document): String? {
        val content = page.select(".single-article--contains-list .single-article__content").firstOrNull() ?: return null
        return content.children()
            .filterNot { moreEventsCta.containsMatchIn(it.text()) }
            .joinToString(" ") { it.text() }
            .trim()
            .ifBlank { null }
    }

    // The listing can print "Image not found" where a card's poster should be while the gig's own
    // page still renders the poster as its article hero - so the page, fetched for the description
    // anyway, is asked before the card's answer is believed.
    private fun articleImage(page: Document, gigUrl: String): String {
        val src = page.select("img.article-image__bg").attr("abs:data-lazy-src")
        check(src.isNotBlank()) { "No poster on the card for $gigUrl, and none on its page either" }
        return src
    }
}

val theGarage = Venue(VenueId("the-garage"), "The Garage")

class TheGarageGigsSource(client: HttpHandler) :
    GigsSource by DhpVenueGigsSource(client, url = "https://www.thegarage.london/live/", venue = theGarage)

val theGrace = Venue(VenueId("the-grace"), "The Grace")

class TheGraceGigsSource(client: HttpHandler) :
    GigsSource by DhpVenueGigsSource(client, url = "https://www.thegrace.london/whats-on/", venue = theGrace)
