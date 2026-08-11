import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class GigEvent(
    val title: String,
    val venue: String,
    val year: Int,
    val month: String,
    val day: String,
    val url: String,
    val imageUrl: String,
) {
    companion object {
        fun of(title: String, venue: String, date: LocalDate, url: String, imageUrl: String) = GigEvent(
            title = title,
            venue = venue,
            year = date.year,
            month = date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
            day = "%02d".format(date.dayOfMonth),
            url = url,
            imageUrl = imageUrl,
        )
    }
}

enum class Genre { Metal, Other }

enum class ClassificationSource { LLM, User }

// one entry in the append-only gig log, keyed by (venue, url) as the stable identity across scrapes
sealed interface GigLogEntry {
    val venue: String
    val url: String
    val recordedAt: Instant
}

// a sighting of a gig at scrape time
data class GigObserved(val gig: GigEvent, override val recordedAt: Instant) : GigLogEntry {
    override val venue get() = gig.venue
    override val url get() = gig.url
}

// a gig's genre, either judged by the LLM classifier or asserted by a user
data class GigClassified(
    override val venue: String,
    override val url: String,
    override val recordedAt: Instant,
    val genre: Genre,
    val source: ClassificationSource,
) : GigLogEntry

// a gig's stable identity across scrapes - everything else (title, date, image) is status, not identity
data class GigId(val venue: String, val url: String)

val GigEvent.id: GigId get() = GigId(venue, url)
val GigLogEntry.id: GigId get() = GigId(venue, url)

private val monthsByShortName = Month.entries.associateBy { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

fun GigEvent.date(): LocalDate = LocalDate.of(year, monthsByShortName.getValue(month), day.toInt())

// Squarespace's "Events List" block sometimes resolves the thumbnail's `src` eagerly and sometimes
// leaves it lazy-loaded with only `data-image` set, depending on the site
private fun Element.squarespaceThumbnailUrl(): String {
    val img = select(".eventlist-column-thumbnail img")
    return img.attr("abs:src").ifBlank { img.attr("abs:data-image") }
}

interface GigsSource {
    val venue: String
    fun latestGigs(): List<GigEvent>
}

class CartAndHorsesGigsSource(private val client: HttpHandler, private val year: Int) : GigsSource {
    private val url = "https://www.cartandhorses.london/news-offers-events/"
    override val venue = "Cart & Horses"

    override fun latestGigs(): List<GigEvent> {
        var currentYear = year
        var previousMonth: String? = null

        return Jsoup.parse(fetchPage(client, url), url)
            .select(".news-carousel__item")
            .filter { it.select(".news-carousel__date-wrap").isNotEmpty() }
            .map { item ->
                val month = item.select(".news-carousel__month").text()
                if (month == "Jan" && previousMonth != null && previousMonth != "Jan") currentYear++
                previousMonth = month

                GigEvent(
                    title = item.select(".news-carousel__link").text(),
                    venue = venue,
                    year = currentYear,
                    month = month,
                    day = item.select(".news-carousel__day").text(),
                    url = item.select(".news-carousel__link").attr("abs:href"),
                    imageUrl = item.select(".news-carousel__image").attr("abs:src"),
                )
            }
    }
}

class NewCrossInnGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://www.newcrossinn.com/gigs/"
    override val venue = "New Cross Inn"

    private val datePattern = Regex("""(\d{2}) (\w{3}) (\d{4})""")

    override fun latestGigs(): List<GigEvent> =
        Jsoup.parse(fetchPage(client, url), url)
            .select("li:has(h3.nci-event-name)")
            .map { item ->
                val (day, month, year) = datePattern.find(item.select("dd").text())!!.destructured
                GigEvent(
                    title = item.select("h3.nci-event-name").text(),
                    venue = venue,
                    year = year.toInt(),
                    month = month,
                    day = day,
                    url = item.select("a:has(h3.nci-event-name)").attr("abs:href"),
                    imageUrl = item.select("img").attr("abs:src"),
                )
            }
}

// shared by every Squarespace "Events List" venue page; the venue-specific classes below just
// supply url/venue
class SquarespaceEventsGigsSource(private val client: HttpHandler, private val url: String, override val venue: String) : GigsSource {
    override fun latestGigs(): List<GigEvent> =
        Jsoup.parse(fetchPage(client, url), url)
            .select("article.eventlist-event--upcoming")
            .map { item ->
                val titleLink = item.select(".eventlist-title-link")
                GigEvent.of(
                    title = titleLink.text(),
                    venue = venue,
                    date = LocalDate.parse(item.select("time.event-date").first()!!.attr("datetime")),
                    url = titleLink.attr("abs:href"),
                    imageUrl = item.squarespaceThumbnailUrl(),
                )
            }
}

class OurBlackHeartGigsSource(client: HttpHandler) :
    GigsSource by SquarespaceEventsGigsSource(client, url = "https://www.ourblackheart.com/events", venue = "Our Black Heart")

class DomeLondonGigsSource(client: HttpHandler) :
    GigsSource by SquarespaceEventsGigsSource(client, url = "https://www.domelondon.co.uk/whatson", venue = "The Dome")

class TheUnderworldGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://www.theunderworldcamden.co.uk/search-events/"
    override val venue = "The Underworld"

    // the site blocks requests without a browser-like User-Agent
    private val browserUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override fun latestGigs(): List<GigEvent> =
        Jsoup.parse(fetchPage(client, url, listOf("User-Agent" to browserUserAgent)), url)
            .select("#gigs article.list")
            .map { item ->
                GigEvent.of(
                    title = item.select(".list-header-title").text(),
                    venue = venue,
                    date = LocalDate.parse(item.select("time").first()!!.attr("datetime")),
                    url = item.select(".list-header-title a").attr("abs:href"),
                    imageUrl = item.select(".list-image img").attr("abs:src"),
                )
            }
}

class ElectricBallroomGigsSource(private val client: HttpHandler, private val year: Int) : GigsSource {
    private val url = "https://electricballroom.co.uk/whats-on/"
    override val venue = "Electric Ballroom"

    // dates have no year, e.g. "Thursday 13th August"; ordinal suffix is discarded
    private val datePattern = Regex("""(\d{1,2})\w*\s+(\w+)""")
    private val backgroundImageUrlPattern = Regex("""url\('([^']+)'\)""")

    override fun latestGigs(): List<GigEvent> {
        var currentYear = year
        var previousMonth: Month? = null

        return Jsoup.parse(fetchPage(client, url), url)
            .select(".grid-block.card")
            .map { item ->
                val (day, monthName) = datePattern.find(item.select(".event-date").text())!!.destructured
                val month = Month.valueOf(monthName.uppercase())
                if (previousMonth != null && month < previousMonth) currentYear++
                previousMonth = month

                GigEvent.of(
                    title = item.select(".event-name a").text(),
                    venue = venue,
                    date = LocalDate.of(currentYear, month, day.toInt()),
                    url = item.select(".event-name a").attr("abs:href"),
                    imageUrl = backgroundImageUrlPattern.find(item.select(".grid-image").attr("style"))?.groupValues?.get(1) ?: "",
                )
            }
    }
}

class DingwallsGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://dingwalls.com/whats-on/"
    override val venue = "Dingwalls"

    // comma placement is inconsistent, e.g. "Wednesday 2nd September 2026", "Tuesday, 8th
    // September 2026", "Saturday 26th September, 2026 (Afternoon Show)"
    private val datePattern = Regex("""(\d{1,2})\w*\s+(\w+),?\s+(\d{4})""")

    override fun latestGigs(): List<GigEvent> =
        Jsoup.parse(fetchPage(client, url), url)
            .select(".gig")
            .map { item ->
                val (day, monthName, year) = datePattern.find(item.select(".elementor-widget-heading:not(.elementor-widget-theme-post-title)").text())!!.destructured

                GigEvent.of(
                    title = item.select(".elementor-widget-theme-post-title a").text(),
                    venue = venue,
                    date = LocalDate.of(year.toInt(), Month.valueOf(monthName.uppercase()), day.toInt()),
                    url = item.select(".elementor-widget-theme-post-title a").attr("abs:href"),
                    imageUrl = item.select(".elementor-widget-theme-post-featured-image img").attr("abs:src"),
                )
            }
}

class TheGarageGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://www.thegarage.london/live/"
    override val venue = "The Garage"

    // e.g. "Fri.14.Aug.26" - two-digit year; some gigs have no image at all, just placeholder text
    private val datePattern = Regex("""\w{3}\.(\d{2})\.(\w{3})\.(\d{2})""")

    override fun latestGigs(): List<GigEvent> =
        Jsoup.parse(fetchPage(client, url), url)
            .select(".card.card--full")
            .map { item ->
                val (day, monthName, year) = datePattern.find(item.select(".card__strip-heading").text())!!.destructured
                val img = item.select(".card__grid-media img")

                GigEvent.of(
                    title = item.select(".card__heading").text(),
                    venue = venue,
                    date = LocalDate.of(2000 + year.toInt(), monthsByShortName.getValue(monthName), day.toInt()),
                    url = item.select(".card__heading").attr("abs:href"),
                    imageUrl = img.attr("abs:data-lazy-src").ifBlank { img.attr("abs:src") },
                )
            }
}

class RoundhouseGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://www.roundhouse.org.uk/whats-on/"
    override val venue = "Roundhouse"

    // e.g. "Wed 12 Aug 26" or a multi-day range "Wed 12 Aug 26–Fri 14 Aug 26"; only the start date is used
    private val datePattern = Regex("""(\d{1,2}) (\w{3}) (\d{2})""")

    override fun latestGigs(): List<GigEvent> =
        Jsoup.parse(fetchPage(client, url), url)
            .select(".event-card")
            .map { item ->
                val link = item.select(".event-card__link")
                val (day, monthName, year) = datePattern.find(item.select(".event-card__date").text())!!.destructured

                GigEvent.of(
                    title = item.select(".event-card__title").text(),
                    venue = venue,
                    date = LocalDate.of(2000 + year.toInt(), monthsByShortName.getValue(monthName), day.toInt()),
                    url = link.attr("abs:href"),
                    imageUrl = item.select(".event-card__image img").attr("abs:src"),
                )
            }
}

// shared by both Signature Brew taprooms - they're listed together on one page, each event
// tagged with its own venue name, so the venue-specific classes below just filter by that
class SignatureBrewGigsSource(private val client: HttpHandler, override val venue: String) : GigsSource {
    private val url = "https://events.signaturebrew.co.uk/"

    private val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)

    // e.g. background-image:url("...") on some events, background-image:none on others (no poster)
    private val backgroundImageUrlPattern = Regex("""url\("([^"]+)"\)""")

    override fun latestGigs(): List<GigEvent> =
        Jsoup.parse(fetchPage(client, url), url)
            .select(".cal-info.w-dyn-item")
            .filter { item -> item.select(".venuename").text() == venue }
            .map { item ->
                val link = item.select("a.button.white.w-button")

                GigEvent.of(
                    title = item.select(".b-show").text(),
                    venue = venue,
                    date = LocalDate.parse(item.select(".dates p.months.date:not(.hide)").text(), dateFormatter),
                    url = link.attr("abs:href"),
                    imageUrl = backgroundImageUrlPattern.find(item.select(".poster").attr("style"))?.groupValues?.get(1) ?: "",
                )
            }
}

class SignatureBrewBlackhorseRoadGigsSource(client: HttpHandler) :
    GigsSource by SignatureBrewGigsSource(client, venue = "Signature Brew Blackhorse Road")

class SignatureBrewHaggerstonGigsSource(client: HttpHandler) :
    GigsSource by SignatureBrewGigsSource(client, venue = "Signature Brew Haggerston")
