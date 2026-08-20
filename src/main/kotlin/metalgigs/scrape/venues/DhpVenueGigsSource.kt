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
    override fun latestGigs(): List<Gig> {
        val listing = Jsoup.parse(fetchPage(client, url), url)

        return (listOf(listing) + laterMonths(listing))
            .flatMap { it.select(".card.card--full") }
            .map { item ->
                val (day, monthName, year) = datePattern.find(item.select(".card__strip-heading").text())!!.destructured
                val heading = item.select(".card__heading")
                // a sold-out gig's heading isn't a link at all - its only link is the "Gig Sold Out"
                // notification, which points at the same gig page
                val gigUrl = heading.attr("abs:href").ifBlank { item.select(".card__notification a").attr("abs:href") }
                val eventPage = Jsoup.parse(fetchPage(client, gigUrl), gigUrl)

                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(heading.text()),
                    GigDate(2000 + year.toInt(), monthsByShortName.getValue(monthName), day.toInt()),
                    poster(item, eventPage, gigUrl),
                    descriptionFrom(eventPage, gigUrl, ::eventPageContent),
                )
            }
    }

    // The listing renders three months of the guide and no more - its own pagination urls
    // (/live/page/2/) serve those same three - so everything later is reachable only through the
    // call its infinite scroll makes: post the last month rendered so far, get the three after it.
    // Past the end of the guide that call keeps answering with months that have no gigs in them
    // rather than with nothing, so the walk stops on the range the page declares rather than on an
    // empty answer. The Garage's listing ended 28 Oct 2026 where its guide ran on to June 2027.
    private fun laterMonths(listing: Document): List<Document> =
        generateSequence(listing) { page ->
            page.select(".guide__month").lastOrNull()
                ?.takeIf { it.yearMonth() < listing.guideEnd() }
                ?.let { monthsAfter(listing, it) }
        }.drop(1).toList()

    // The guide's own parameters stay on the listing - a fragment carries months and nothing else -
    // so they're read from there however far the walk has gone.
    //
    // form() sets the body but not the content type, and the endpoint fills $_POST from that header
    // alone. Without it the call still answers 200, with three months dated data-year="0" - which
    // the walk would ask to continue from and be answered with for ever - so the months coming back
    // are checked to be after the one asked for rather than taken on trust.
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

    // Four places a gig's poster can come from, in the order they're asked. A blank card isn't the
    // last word: the listing can print "Image not found" where a card's poster should be while the
    // gig's own page still renders it as the article hero, so that page - fetched for the
    // description anyway - is asked next rather than the gig being given up on.
    //
    // The guide walk reaches gigs announced before anyone has drawn artwork for them, which have
    // none of the three: The Garage's event page prints the same "Image not found" in its own hero.
    // A venue with a house image of its own stands that in, so such a gig is published showing the
    // room it's in rather than being dropped or failing the whole listing. A venue without one
    // still fails, which is the answer for The Grace, whose site has no such image to use.
    private fun poster(card: Element, eventPage: Document, gigUrl: String): PosterUrl {
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
