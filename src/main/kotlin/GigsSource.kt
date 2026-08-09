import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class GigEvent(val title: String, val venue: String, val year: Int, val month: String, val day: String, val url: String) {
    companion object {
        fun of(title: String, venue: String, date: LocalDate, url: String) = GigEvent(
            title = title,
            venue = venue,
            year = date.year,
            month = date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
            day = "%02d".format(date.dayOfMonth),
            url = url,
        )
    }
}

interface GigsSource {
    fun latestGigs(): List<GigEvent>
}

class CartAndHorsesGigsSource(private val client: HttpHandler, private val year: Int) : GigsSource {
    private val url = "https://www.cartandhorses.london/news-offers-events/"
    private val venue = "Cart & Horses"

    override fun latestGigs(): List<GigEvent> =
        Jsoup.parse(fetchPage(client, url), url)
            .select(".news-carousel__item")
            .filter { it.select(".news-carousel__date-wrap").isNotEmpty() }
            .map { item ->
                GigEvent(
                    title = item.select(".news-carousel__link").text(),
                    venue = venue,
                    year = year,
                    month = item.select(".news-carousel__month").text(),
                    day = item.select(".news-carousel__day").text(),
                    url = item.select(".news-carousel__link").attr("abs:href"),
                )
            }
}

class NewCrossInnGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://www.newcrossinn.com/gigs/"
    private val venue = "New Cross Inn"

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
                    url = item.select("a").first()!!.attr("abs:href"),
                )
            }
}

class OurBlackHeartGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://www.ourblackheart.com/events"
    private val venue = "Our Black Heart"

    override fun latestGigs(): List<GigEvent> =
        Jsoup.parse(fetchPage(client, url), url)
            .select("article.eventlist-event--upcoming")
            .map { item ->
                GigEvent.of(
                    title = item.select(".eventlist-title-link").text(),
                    venue = venue,
                    date = LocalDate.parse(item.select("time.event-date").first()!!.attr("datetime")),
                    url = item.select(".eventlist-title-link").attr("abs:href"),
                )
            }
}

class TheUnderworldGigsSource(private val client: HttpHandler) : GigsSource {
    private val url = "https://www.theunderworldcamden.co.uk/search-events/"
    private val venue = "The Underworld"

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
                )
            }
}
