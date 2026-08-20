package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

val alexandraPalace = Venue(VenueId("alexandra-palace"), "Alexandra Palace")

class AlexandraPalaceGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = alexandraPalace

    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url, listOf("User-Agent" to browserUserAgent)), url)
            .select(".event_card_wrapper")
            .map { item ->
                val link = item.select(".event_target")
                val gigUrl = link.attr("abs:href")

                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(link.text()),
                    startDateOf(item.select(".dates").text()),
                    posterUrlFrom(gigUrl, item.widestImageUrl()),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }

    private val url = "https://www.alexandrapalace.com/whats-on/"

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
    private fun startDateOf(text: String): GigDate {
        val trimmed = text.trim()
        val rangeSplit = trimmed.split("-", limit = 2).map { it.trim() }
        if (rangeSplit.size == 1) {
            val (day, month, year) = singleDatePattern.find(trimmed)!!.destructured
            return GigDate(year.toInt(), monthsByShortName.getValue(month), day.toInt())
        }

        val (startLeft, endText) = rangeSplit
        val (_, endMonthName, yearText) = singleDatePattern.find(endText)!!.destructured
        val startParts = startLeft.split(Regex("""\s+"""))
        val startDay = startParts[0].toInt()
        val startMonthName = startParts.getOrElse(1) { endMonthName }
        val startMonth = monthsByShortName.getValue(startMonthName)
        val endMonth = monthsByShortName.getValue(endMonthName)
        val startYear = if (startMonth > endMonth) yearText.toInt() - 1 else yearText.toInt()
        return GigDate(startYear, startMonth, startDay)
    }

    // the img tag's own src is a 650px thumbnail; srcset carries the same image up to 2048px, so
    // the widest entry is used instead - the same reasoning as dropping The Underworld's imgix w=
    // parameter, just a different mechanism for the same problem. A couple of events have no image
    // at all - no img tag, not just a missing srcset.
    private fun Element.widestImageUrl(): String {
        val img = select(".event_img img")
        val widest = img.attr("srcset").split(",").mapNotNull { entry ->
            val parts = entry.trim().split(Regex("""\s+"""))
            val w = parts.getOrNull(1)?.removeSuffix("w")?.toIntOrNull()
            if (parts.isNotEmpty() && w != null) w to parts[0] else null
        }.maxByOrNull { it.first }?.second
        return widest ?: img.attr("abs:src")
    }
}
