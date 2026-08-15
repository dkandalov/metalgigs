import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class GigTitle(val value: String) {
    override fun toString() = value
}

data class Gig(
    val id: GigId,
    val title: GigTitle,
    val date: LocalDate,
    val imageUrl: String,
    val description: String,
)

enum class Genre { Metal, Other }

enum class ClassificationSource { LLM, User }

data class GigId(val venueId: VenueId, val url: String) {
    init {
        require(venueId.value.isNotBlank()) { "Gig has no venue, so it can't be identified: $url" }
        require(url.isNotBlank()) { "Gig has no url, so it can't be identified: gig at $venueId" }
    }
}

sealed interface LogEntry {
    val recordedAt: Instant
}

data class GigObserved(val gig: Gig, override val recordedAt: Instant) : LogEntry {
    val id get() = gig.id
}

data class GigClassified(
    val id: GigId,
    override val recordedAt: Instant,
    val genre: Genre,
    val source: ClassificationSource,
    val llmModel: String? = null,
    val useVision: Boolean? = null,
) : LogEntry

// logicalDate is the date the page was rendered as of - gigs before it are left off - which is
// today for a normal render but any date for a backdated one. Distinct from recordedAt, the wall
// clock: without it two renders of very different pages are told apart only by their gig count.
data class GigsRendered(
    val file: String,
    val gigCount: Int,
    val logicalDate: LocalDate,
    override val recordedAt: Instant,
) : LogEntry

private val monthsByShortName = Month.entries.associateBy { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

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

// Squarespace's "Events List" block sometimes resolves the thumbnail's `src` eagerly and sometimes
// leaves it lazy-loaded with only `data-image` set, depending on the site
private fun Element.squarespaceThumbnailUrl(): String {
    val img = select(".eventlist-column-thumbnail img")
    return img.attr("abs:src").ifBlank { img.attr("abs:data-image") }
}

interface GigsSource {
    val venue: Venue
    fun latestGigs(): List<Gig>
}

// Jsoup returns an empty selection rather than null when nothing matches, and an empty selection's
// text is "", which would pass for an event page that says nothing about its gig.
internal fun Elements.textOrNull(): String? = if (isEmpty()) null else text()

// one dead event page shouldn't cost the whole scrape - the gig is still worth recording, and one
// with a poster can still be classified from that. A page whose markup no longer matches the
// venue's own selectors comes to the same thing: no text, counted in the scrape summary
internal fun fetchDescription(client: HttpHandler, url: String, content: (Document) -> String?): String =
    runCatching { content(Jsoup.parse(fetchPage(client, url), url)) }.getOrNull() ?: ""

val cartAndHorses = Venue(VenueId("cart-and-horses"), "Cart & Horses")

class CartAndHorsesGigsSource(private val client: HttpHandler, private val year: Int) : GigsSource {
    private val url = "https://www.cartandhorses.london/news-offers-events/"
    override val venue = cartAndHorses

    // The Useyourlocal pub-website platform scopes an event page to nothing, so the whole page's
    // text carries the nav and footer - opening times, address, social links - into every gig.
    internal fun eventPageContent(page: Document) = page.select(".page_header, .page_content_inner").textOrNull()

    override fun latestGigs(): List<Gig> {
        var currentYear = year
        var previousMonth: String? = null

        return Jsoup.parse(fetchPage(client, url), url)
            .select(".news-carousel__item")
            .filter { it.select(".news-carousel__date-wrap").isNotEmpty() }
            .map { item ->
                val month = item.select(".news-carousel__month").text()
                if (month == "Jan" && previousMonth != null && previousMonth != "Jan") currentYear++
                previousMonth = month
                val gigUrl = item.select(".news-carousel__link").attr("abs:href")

                Gig(
                    id = GigId(venue.id, gigUrl),
                    title = GigTitle(item.select(".news-carousel__link").text()),
                    date = LocalDate.of(currentYear, monthsByShortName.getValue(month), item.select(".news-carousel__day").text().toInt()),
                    imageUrl = item.select(".news-carousel__image").attr("abs:src"),
                    description = fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
    }
}

val newCrossInn = Venue(VenueId("new-cross-inn"), "New Cross Inn")

class NewCrossInnGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://www.newcrossinn.com/gigs/"
    override val venue = newCrossInn

    private val datePattern = Regex("""(\d{2}) (\w{3}) (\d{4})""")

    // pit.live renders the description client-side via Alpine.js: the text sits in an x-html
    // attribute rather than as element text, so the page's own text never contains it.
    internal fun eventPageContent(page: Document) = page.select("[x-ref=desc]").firstOrNull()?.attr("x-html")

    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select("li:has(h3.nci-event-name)")
            .map { item ->
                val (day, month, year) = datePattern.find(item.select("dd").text())!!.destructured
                val gigUrl = item.select("a:has(h3.nci-event-name)").attr("abs:href")
                Gig(
                    id = GigId(venue.id, gigUrl),
                    title = GigTitle(item.select("h3.nci-event-name").text()),
                    date = LocalDate.of(year.toInt(), monthsByShortName.getValue(month), day.toInt()),
                    imageUrl = item.select("img").attr("abs:src"),
                    description = fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
}

// most Squarespace venues write a gig's blurb on its own event page, but The Fiddler's Elbow leaves
// every one of those empty and puts the whole thing in the listing's excerpt instead - which also
// means one request for the listing rather than one more per gig
enum class SquarespaceDescription { EventPage, ListingExcerpt }

// shared by every Squarespace "Events List" venue page; the venue-specific classes below just
// supply url/venue
class SquarespaceEventsGigsSource(
    private val client: HttpHandler,
    private val url: String,
    override val venue: Venue,
    private val descriptionFrom: SquarespaceDescription = SquarespaceDescription.EventPage,
) : GigsSource {
    // article.eventitem also holds the template's own event metadata - the date in both 12- and
    // 24-hour form, the venue's postal address, Google Calendar and ICS links - which is longer
    // than some gigs' actual blurb. The title isn't in here either, but the classifier is given it
    // separately.
    internal fun eventPageContent(page: Document) = page.select(".eventitem-column-content").textOrNull()

    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select("article.eventlist-event--upcoming")
            .map { item ->
                val titleLink = item.select(".eventlist-title-link")
                val gigUrl = titleLink.attr("abs:href")
                Gig(
                    id = GigId(venue.id, gigUrl),
                    title = GigTitle(titleLink.text()),
                    date = LocalDate.parse(item.select("time.event-date").first()!!.attr("datetime")),
                    imageUrl = item.squarespaceThumbnailUrl(),
                    description = when (descriptionFrom) {
                        SquarespaceDescription.EventPage -> fetchDescription(client, gigUrl, ::eventPageContent)
                        SquarespaceDescription.ListingExcerpt -> item.select(".eventlist-excerpt").text()
                    },
                )
            }
}

val ourBlackHeart = Venue(VenueId("our-black-heart"), "Our Black Heart")

class OurBlackHeartGigsSource(client: HttpHandler) :
    GigsSource by SquarespaceEventsGigsSource(client, url = "https://www.ourblackheart.com/events", venue = ourBlackHeart)

val theDome = Venue(VenueId("dome"), "The Dome")

class DomeLondonGigsSource(client: HttpHandler) :
    GigsSource by SquarespaceEventsGigsSource(client, url = "https://www.domelondon.co.uk/whatson", venue = theDome)

val fiddlersElbow = Venue(VenueId("fiddlers-elbow"), "The Fiddler's Elbow")

class FiddlersElbowGigsSource(client: HttpHandler) :
    GigsSource by SquarespaceEventsGigsSource(
        client,
        url = "https://www.thefiddlerselbow.co.uk/whos-playing",
        venue = fiddlersElbow,
        descriptionFrom = SquarespaceDescription.ListingExcerpt,
    )

val theUnderworld = Venue(VenueId("underworld"), "The Underworld")

class TheUnderworldGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://www.theunderworldcamden.co.uk/search-events/"
    override val venue = theUnderworld

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

    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url, listOf("User-Agent" to browserUserAgent)), url)
            .select("#gigs article.list")
            .map { item ->
                val gigUrl = item.select(".list-header-title a").attr("abs:href")
                Gig(
                    id = GigId(venue.id, gigUrl),
                    title = GigTitle(item.select(".list-header-title").text()),
                    date = LocalDate.parse(item.select("time").first()!!.attr("datetime")),
                    imageUrl = imgixUrlWithoutWidth(item.select(".list-image img").attr("abs:src")),
                    description = fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
}

val electricBallroom = Venue(VenueId("electric-ballroom"), "Electric Ballroom")

class ElectricBallroomGigsSource(private val client: HttpHandler, private val year: Int) : GigsSource {
    private val url = "https://electricballroom.co.uk/whats-on/"
    override val venue = electricBallroom

    // dates have no year, e.g. "Thursday 13th August"; ordinal suffix is discarded
    private val datePattern = Regex("""(\d{1,2})\w*\s+(\w+)""")
    private val backgroundImageUrlPattern = Regex("""url\('([^']+)'\)""")

    // The listing's own copy is a few paragraphs in .article-content - the title above it repeats
    // the gig's name, and the door time, price and Buy Tickets sit outside it. One of those
    // paragraphs is the venue's age and ID policy, which nothing in the markup tells apart from the
    // copy, so it goes by its wording: "Please note this show is 14+...", "Strictly 18+ / physical
    // photo ID required at entry", "Proof of age is required at entry" all appear across the
    // listings, and on a thin one the policy is most of the text.
    private val agePolicy = Regex("""please note this show is|strictly \d+\+|proof of age|photo id""", RegexOption.IGNORE_CASE)

    internal fun eventPageContent(page: Document): String? {
        val content = page.select(".article-content").firstOrNull()?.clone() ?: return null
        content.select("p").filter { agePolicy.containsMatchIn(it.text()) }.forEach { it.remove() }
        return content.text().ifBlank { null }
    }

    override fun latestGigs(): List<Gig> {
        var currentYear = year
        var previousMonth: Month? = null

        return Jsoup.parse(fetchPage(client, url), url)
            .select(".grid-block.card")
            .map { item ->
                val (day, monthName) = datePattern.find(item.select(".event-date").text())!!.destructured
                val month = Month.valueOf(monthName.uppercase())
                if (previousMonth != null && month < previousMonth) currentYear++
                previousMonth = month

                val gigUrl = item.select(".event-name a").attr("abs:href")
                Gig(
                    id = GigId(venue.id, gigUrl),
                    title = GigTitle(item.select(".event-name a").text()),
                    date = LocalDate.of(currentYear, month, day.toInt()),
                    imageUrl = backgroundImageUrlPattern.find(item.select(".grid-image").attr("style"))?.groupValues?.get(1) ?: "",
                    description = fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
    }
}

val dingwalls = Venue(VenueId("dingwalls"), "Dingwalls")

class DingwallsGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://dingwalls.com/whats-on/"
    override val venue = dingwalls

    // comma placement is inconsistent, e.g. "Wednesday 2nd September 2026", "Tuesday, 8th
    // September 2026", "Saturday 26th September, 2026 (Afternoon Show)"
    private val datePattern = Regex("""(\d{1,2})\w*\s+(\w+),?\s+(\d{4})""")

    internal fun eventPageContent(page: Document) = page.select(".elementor-location-single").textOrNull()

    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select(".gig")
            .map { item ->
                val (day, monthName, year) = datePattern.find(item.select(".elementor-widget-heading:not(.elementor-widget-theme-post-title)").text())!!.destructured
                val gigUrl = item.select(".elementor-widget-theme-post-title a").attr("abs:href")

                Gig(
                    id = GigId(venue.id, gigUrl),
                    title = GigTitle(item.select(".elementor-widget-theme-post-title a").text()),
                    date = LocalDate.of(year.toInt(), Month.valueOf(monthName.uppercase()), day.toInt()),
                    imageUrl = item.select(".elementor-widget-theme-post-featured-image img").attr("abs:src"),
                    description = fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
}

// shared by DHP Family's venue sites, which all use the same card markup; the venue-specific
// classes below just supply url/venue
class DhpVenueGigsSource(private val client: HttpHandler, private val url: String, override val venue: Venue) : GigsSource {
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

                Gig(
                    id = GigId(venue.id, gigUrl),
                    title = GigTitle(heading.text()),
                    date = LocalDate.of(2000 + year.toInt(), monthsByShortName.getValue(monthName), day.toInt()),
                    imageUrl = img.attr("abs:data-lazy-src").ifBlank { img.attr("abs:src") },
                    description = fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
}

val theGarage = Venue(VenueId("the-garage"), "The Garage")

class TheGarageGigsSource(client: HttpHandler) :
    GigsSource by DhpVenueGigsSource(client, url = "https://www.thegarage.london/live/", venue = theGarage)

val theGrace = Venue(VenueId("the-grace"), "The Grace")

class TheGraceGigsSource(client: HttpHandler) :
    GigsSource by DhpVenueGigsSource(client, url = "https://www.thegrace.london/whats-on/", venue = theGrace)

val roundhouse = Venue(VenueId("roundhouse"), "Roundhouse")

class RoundhouseGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://www.roundhouse.org.uk/whats-on/"
    override val venue = roundhouse

    // e.g. "Wed 12 Aug 26" or a multi-day range "Wed 12 Aug 26–Fri 14 Aug 26"; only the start date is used
    private val datePattern = Regex("""(\d{1,2}) (\w{3}) (\d{2})""")

    // The "Related events" carousel is nested inside .event-about rather than sitting beside it, so
    // excluding that one block by class is what keeps other shows' titles out.
    internal fun eventPageContent(page: Document) =
        page.select(".event-hero__heading-wrapper, section.event-about .layout-block:not(.layout-block--related-events-list)").textOrNull()

    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select(".event-card")
            .map { item ->
                val link = item.select(".event-card__link")
                val (day, monthName, year) = datePattern.find(item.select(".event-card__date").text())!!.destructured

                val gigUrl = link.attr("abs:href")
                Gig(
                    id = GigId(venue.id, gigUrl),
                    title = GigTitle(item.select(".event-card__title").text()),
                    date = LocalDate.of(2000 + year.toInt(), monthsByShortName.getValue(monthName), day.toInt()),
                    imageUrl = item.select(".event-card__image img").attr("abs:src"),
                    description = fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
}

// shared by both Signature Brew taprooms - they're listed together on one page, each event
// tagged with its own venue name, so the venue-specific classes below just filter by that
class SignatureBrewGigsSource(private val client: HttpHandler, override val venue: Venue) : GigsSource {
    private val url = "https://events.signaturebrew.co.uk/"

    private val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)

    // e.g. background-image:url("...") on some events, background-image:none on others (no poster)
    private val backgroundImageUrlPattern = Regex("""url\("([^"]+)"\)""")

    internal fun eventPageContent(page: Document) = page.text()

    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select(".cal-info.w-dyn-item")
            .filter { item -> item.select(".venuename").text() == venue.name }
            .map { item ->
                val link = item.select("a.button.white.w-button")
                val gigUrl = link.attr("abs:href")

                Gig(
                    id = GigId(venue.id, gigUrl),
                    title = GigTitle(item.select(".b-show").text()),
                    date = LocalDate.parse(item.select(".dates p.months.date:not(.hide)").text(), dateFormatter),
                    imageUrl = backgroundImageUrlPattern.find(item.select(".poster").attr("style"))?.groupValues?.get(1) ?: "",
                    description = fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
}

val unionChapel = Venue(VenueId("union-chapel"), "Union Chapel")

class UnionChapelGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://unionchapel.org.uk/whats-on"
    override val venue = unionChapel

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

    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            // every card carries its own sortable timestamp for the page's client-side sorting,
            // which beats parsing the human date ("Thu 27 May 2027") printed alongside it
            .select(".item[data-chron]")
            .map { item ->
                // matched on the path, since the other link on a card goes to whichever external
                // ticketing site that gig happens to sell through
                val gigUrl = item.select("a[href*=/whats-on/]").attr("abs:href")
                Gig(
                    id = GigId(venue.id, gigUrl),
                    // each card prints its title twice, once for the card and once for the hover
                    // panel inside it, so this takes the first rather than both concatenated
                    title = GigTitle(item.select(".card-title").first()!!.text()),
                    date = LocalDate.parse(item.attr("data-chron").substringBefore(' ')),
                    imageUrl = backgroundImageUrlPattern.find(item.select(".card-image").attr("style"))?.groupValues?.get(1) ?: "",
                    description = fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
}

val scala = Venue(VenueId("scala"), "Scala")

class ScalaGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://scala.co.uk/events/categories/live-music/"
    override val venue = scala

    // e.g. "19th August 2026" - full month name, ordinal suffix discarded
    private val datePattern = Regex("""(\d{1,2})\w*\s+(\w+)\s+(\d{4})""")

    // e.g. background-image:url('...') - the poster is a css background rather than an img element
    private val backgroundImageUrlPattern = Regex("""url\('([^']+)'\)""")

    // this category currently spans two pages (36 + 19 events), found by following the page's own
    // "next" link rather than guessing at a query parameter - the same sidebar also links a handful
    // of upcoming shows outside .tb-event-item, which .select scopes past. maxPages exists only to
    // bound a pathological site bug; the real stop condition is the next link disappearing
    private val maxPages = 10

    // .entry-content leads with the venue's ticketing and access furniture - price and sale status,
    // door times, an age/ID/access table, "Read our guide to buying and using tickets" - and closes
    // with calendar links, all of it identical across listings and on a short one longer than the
    // copy. The gig's own copy is what follows the "About <artist>" heading, on every listing read.
    // The header box is kept for the promoter and support acts, which the listing's title leaves out.
    // Its own date line and buy/info links go with the rest of the furniture though: the date is
    // already a field on the gig, and reaching the classifier as prose only reads as a second date.
    private val ticketingFurniture =
        "p.event-date, p.age-restrictions, p.event-time, p.guide-to, p.add-calendar, .button, .left-morebox, .right-morebox"

    internal fun eventPageContent(page: Document): String? {
        val content = page.select(".event-post .entry-content").firstOrNull()?.clone() ?: return null
        content.select(ticketingFurniture).remove()
        val lineup = content.select(".tb-event-headerbox").also { it.remove() }.text()

        val children = content.children()
        val about = children.indexOfFirst { it.tagName() == "h3" && it.text().startsWith("About", ignoreCase = true) }
        val copy = (if (about >= 0) children.drop(about) else children).joinToString(" ") { it.text() }

        return "$lineup $copy".trim().ifBlank { null }
    }

    override fun latestGigs(): List<Gig> {
        val gigs = mutableListOf<Gig>()
        var pageUrl: String? = url
        var pagesFetched = 0

        while (pageUrl != null && pagesFetched < maxPages) {
            val page = Jsoup.parse(fetchPage(client, pageUrl), pageUrl)
            pagesFetched++
            gigs += page.select(".tb-event-item").map { item ->
                val (day, monthName, year) = datePattern.find(item.select(".date").text())!!.destructured
                val link = item.select("h2 a")
                val gigUrl = link.attr("abs:href")

                Gig(
                    id = GigId(venue.id, gigUrl),
                    title = GigTitle(link.text()),
                    date = LocalDate.of(year.toInt(), Month.valueOf(monthName.uppercase()), day.toInt()),
                    imageUrl = backgroundImageUrlPattern.find(item.select(".tb-event-feature-pic").attr("style"))?.groupValues?.get(1) ?: "",
                    description = fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
            pageUrl = page.select(".em-pagination a.next").attr("abs:href").ifBlank { null }
        }
        return gigs
    }
}

val alexandraPalace = Venue(VenueId("alexandra-palace"), "Alexandra Palace")

class AlexandraPalaceGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://www.alexandrapalace.com/whats-on/"
    override val venue = alexandraPalace

    // the site blocks requests without a browser-like User-Agent
    private val browserUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val singleDatePattern = Regex("""(\d{1,2})\s+(\w+)\s+(\d{4})""")

    // #event_content, the obvious container, also holds a sidebar of quick-link buttons ("Buy
    // Tickets", "FAQs", "Accessibility") repeated identically on every event page, and the whole
    // page's text adds the sitewide nav on top. These name the two containers that hold anything
    // gig-specific: the description block, and the "Key information" accordion, often an artist bio.
    internal fun eventPageContent(page: Document) = page.select(".ap_text_block, #key-information").textOrNull()

    // dates are either a single day ("21 Aug 2026") or a range, and a range is either same-month
    // ("1 - 9 Aug 2026") or cross-month ("11 Dec - 3 Jan 2027") - only the start date is used. The
    // year is only ever written once, on the end date, which is wrong for a cross-month range that
    // crosses a calendar year boundary: "11 Dec - 3 Jan 2027" starts in 2026, not 2027, so the start
    // year is rolled back a year whenever the start month sorts after the end month
    private fun startDateOf(text: String): LocalDate {
        val trimmed = text.trim()
        val rangeSplit = trimmed.split("-", limit = 2).map { it.trim() }
        if (rangeSplit.size == 1) {
            val (day, month, year) = singleDatePattern.find(trimmed)!!.destructured
            return LocalDate.of(year.toInt(), monthsByShortName.getValue(month), day.toInt())
        }

        val (startLeft, endText) = rangeSplit
        val (_, endMonthName, yearText) = singleDatePattern.find(endText)!!.destructured
        val startParts = startLeft.split(Regex("""\s+"""))
        val startDay = startParts[0].toInt()
        val startMonthName = startParts.getOrElse(1) { endMonthName }
        val startMonth = monthsByShortName.getValue(startMonthName)
        val endMonth = monthsByShortName.getValue(endMonthName)
        val startYear = if (startMonth > endMonth) yearText.toInt() - 1 else yearText.toInt()
        return LocalDate.of(startYear, startMonth, startDay)
    }

    // the img tag's own src is a 650px thumbnail; srcset carries the same image up to 2048px, so
    // the widest entry is used instead - the same reasoning as dropping The Underworld's imgix w=
    // parameter, just a different mechanism for the same problem. A couple of events have no image
    // at all (no img tag, not just a missing srcset), so this falls back to "" like everywhere else
    private fun Element.widestImageUrl(): String {
        val img = select(".event_img img")
        val widest = img.attr("srcset").split(",").mapNotNull { entry ->
            val parts = entry.trim().split(Regex("""\s+"""))
            val w = parts.getOrNull(1)?.removeSuffix("w")?.toIntOrNull()
            if (parts.isNotEmpty() && w != null) w to parts[0] else null
        }.maxByOrNull { it.first }?.second
        return widest ?: img.attr("abs:src")
    }

    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url, listOf("User-Agent" to browserUserAgent)), url)
            .select(".event_card_wrapper")
            .map { item ->
                val link = item.select(".event_target")
                val gigUrl = link.attr("abs:href")

                Gig(
                    id = GigId(venue.id, gigUrl),
                    title = GigTitle(link.text()),
                    date = startDateOf(item.select(".dates").text()),
                    imageUrl = item.widestImageUrl(),
                    description = fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
}

val paperDressVintage = Venue(VenueId("paper-dress-vintage"), "Paper Dress Vintage")

class PaperDressVintageGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://paperdressvintage.co.uk/by-night"
    override val venue = paperDressVintage

    // e.g. "Fri 14 Aug" - no year on the card itself; each month's block of cards is wrapped in a
    // div carrying the year, e.g. data-event-month="August 2026"
    private val dayPattern = Regex("""\w{3}\s+(\d{1,2})\s+\w{3}""")
    private val backgroundImageUrlPattern = Regex("""url\('([^']+)'\)""")

    // the listing's own poster is a 550x300 thumbnail - below the 768px render target. Every one is
    // named "<original>-lbox-<W>x<H>-FFF.<ext>"; stripping that suffix recovers the original upload
    // (measured: 800px-2560px across a sample) with no extra request, the same trick as dropping The
    // Underworld's imgix w= parameter
    private val thumbnailSuffixPattern = Regex("""-lbox-\d+x\d+-FFF(\.\w+)$""")

    internal fun eventPageContent(page: Document) = page.select(".event__content").textOrNull()

    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select("[data-event-month]")
            .flatMap { monthBlock ->
                val (monthName, year) = monthBlock.attr("data-event-month").split(" ")
                monthBlock.select(".events__event").map { item ->
                    val day = dayPattern.find(item.select("p.margin__bottom--1").text())!!.groupValues[1]
                    val thumbnailUrl = backgroundImageUrlPattern.find(item.select(".event__poster").attr("style"))?.groupValues?.get(1) ?: ""
                    val gigUrl = item.attr("abs:href")

                    Gig(
                        id = GigId(venue.id, gigUrl),
                        title = GigTitle(item.select("h4").text()),
                        date = LocalDate.of(year.toInt(), Month.valueOf(monthName.uppercase()), day.toInt()),
                        imageUrl = thumbnailSuffixPattern.replace(thumbnailUrl, "$1"),
                        description = fetchDescription(client, gigUrl, ::eventPageContent),
                    )
                }
            }
}

val signatureBrewBlackhorseRoad = Venue(VenueId("signature-brew-blackhorse-road"), "Signature Brew Blackhorse Road")

class SignatureBrewBlackhorseRoadGigsSource(client: HttpHandler) :
    GigsSource by SignatureBrewGigsSource(client, venue = signatureBrewBlackhorseRoad)

val signatureBrewHaggerston = Venue(VenueId("signature-brew-haggerston"), "Signature Brew Haggerston")

class SignatureBrewHaggerstonGigsSource(client: HttpHandler) :
    GigsSource by SignatureBrewGigsSource(client, venue = signatureBrewHaggerston)
