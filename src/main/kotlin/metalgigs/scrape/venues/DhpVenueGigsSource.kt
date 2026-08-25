package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Uri
import org.http4k.core.body.form
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// shared by DHP Family's venue sites, which all use the same card markup
internal class DhpVenueGigsSource(
    private val client: HttpHandler,
    private val url: String,
    override val venue: Venue,
    private val venueImage: PosterUrl? = null,
) : GigsSource {
    // DHP puts every venue gig under /gigs/ whatever the listing itself is called: The Garage lists
    // at /live/ and The Grace at /whats-on/, and both link their gigs to /gigs/.
    private val gigsPath = url.split("/").take(3).joinToString("/") + "/gigs/"

    override fun latestGigs(): List<Gig> {
        val listing = Jsoup.parse(fetchPage(client, url), url)

        return (listOf(listing) + laterMonths(listing))
            .flatMap { it.select(".card.card--full") }
            .map { item ->
                val (day, monthName, year) = datePattern.find(item.select(".card__strip-heading").text())!!.destructured
                val heading = item.select(".card__heading")
                // a sold-out gig's heading isn't a link at all - its only link is the "Gig Sold Out"
                // notification, which points at the same gig page
                val gigUrl = gigUrlFrom(
                    heading.attr("abs:href").ifBlank { item.select(".card__notification a").attr("abs:href") },
                    gigsPath,
                )
                val eventPage = Jsoup.parse(fetchPage(client, gigUrl.value), gigUrl.value)

                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(heading.text()),
                    GigDate(2000 + year.toInt(), monthsByShortName.getValue(monthName), day.toInt()),
                    poster(item, eventPage, gigUrl),
                    descriptionFrom(eventPage, gigUrl, ::eventPageContent),
                )
            }
    }

    // Why the walk stops on the declared range: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
    private fun laterMonths(listing: Document): List<Document> =
        generateSequence(listing) { page ->
            page.select(".guide__month").lastOrNull()
                ?.takeIf { it.yearMonth() < listing.guideEnd() }
                ?.let { monthsAfter(listing, it) }
        }.drop(1).toList()

    // form() sets the body but not the content type, and the endpoint fills $_POST from that header
    // alone: without it the call answers 200 with three months dated data-year="0".
    private fun monthsAfter(listing: Document, month: Element): Document =
        Jsoup.parse(
            client(
                Request(POST, guideUrl)
                    .header("content-type", "application/x-www-form-urlencoded")
                    .form("guide_post_type", listing.guideValue("guide_post_type"))
                    .form("guide_post_type_date_field", listing.guideValue("guide_post_type_date_field"))
                    .form("guide_prev_month", month.attr("data-month"))
                    .form("guide_prev_year", month.attr("data-year"))
            ).bodyString(),
            url,
        ).also { months ->
            val last = months.select(".guide__month").lastOrNull() ?: return@also
            check(last.yearMonth() > month.yearMonth()) {
                "The guide at $url answered ${month.attr("data-month")} ${month.attr("data-year")} with months up to " +
                    "${last.attr("data-month")} ${last.attr("data-year")}, which is no later - it no longer pages forward"
            }
        }

    // both DHP sites run the same theme, so this path differs only by the venue's own host
    private val guideUrl = "${Uri.of(url).path("").query("")}/wp-content/themes/dhp/includes/ajax/ajax_guide.php"

    private fun Document.guideValue(name: String): String =
        select(".js-guide-container input[name=$name]").attr("value")
            .ifBlank { error("No $name on the guide at $url - the venue's listing no longer declares it") }

    private fun Document.guideEnd(): Int = guideValue("guide_end_year").toInt() * 100 + guideValue("guide_end_year_end_month").toInt()

    private fun Element.yearMonth(): Int = attr("data-year").toInt() * 100 + attr("data-month-number").toInt()

    // e.g. "Fri.14.Aug.26" - two-digit year; some gigs have no image at all, just placeholder text
    private val datePattern = Regex("""\w{3}\.(\d{2})\.(\w{3})\.(\d{2})""")

    // The outer wrapper div carries ".single-article" too, so selecting that doubles every word of
    // the text; only the inner section has the list-contains class.
    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    private val moreEventsCta = Regex("""for more events|check out what.s on here""", RegexOption.IGNORE_CASE)

    internal fun eventPageContent(page: Document): String? {
        val content = page.select(".single-article--contains-list .single-article__content").firstOrNull() ?: return null
        return content.children()
            .filterNot { moreEventsCta.containsMatchIn(it.text()) }
            .joinToString(" ") { it.text() }
            .trim()
            .ifBlank { null }
    }

    // Why four places, in this order: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
    private fun poster(card: Element, eventPage: Document, gigUrl: GigUrl): PosterUrl {
        val cardImage = card.select(".card__grid-media img")
        val src = cardImage.attr("abs:data-lazy-src")
            .ifBlank { cardImage.attr("abs:src") }
            .ifBlank { eventPage.select("img.article-image__bg").attr("abs:data-lazy-src") }
        if (src.isNotBlank()) return PosterUrl(src)

        checkNotNull(venueImage) { "No poster on the card for $gigUrl, and none on its page either" }
        println("Using $venue's own image for $gigUrl - no poster on its card or its page")
        return venueImage
    }
}

val theGarage = Venue(VenueId("the-garage"), "The Garage")

class TheGarageGigsSource(client: HttpHandler) :
    GigsSource by DhpVenueGigsSource(
        client,
        url = "https://www.thegarage.london/live/",
        venue = theGarage,
        // the venue's own crowd shot, from its own media library, so a gig whose artwork doesn't
        // exist yet shows the room it's in rather than nothing or some other band's poster
        venueImage = PosterUrl("https://www.thegarage.london/wp-content/uploads/2020/03/gigs-front@3x.jpg"),
    )

val theGrace = Venue(VenueId("the-grace"), "The Grace")

class TheGraceGigsSource(client: HttpHandler) :
    GigsSource by DhpVenueGigsSource(client, url = "https://www.thegrace.london/whats-on/", venue = theGrace)
