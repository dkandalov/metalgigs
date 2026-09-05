package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val bushHall = Venue(VenueId("bush-hall"), "Bush Hall")

// Why See Tickets rather than the venue's own site: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
class BushHallGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = bushHall

    override fun latestGigs(): List<Gig> {
        val listing = Jsoup.parse(fetchPage(client, url, browserHeaders), url)
        checkSinglePage(listing)

        return listing.select("ul.search-results li.g-blocklist-item")
            .map { card ->
                // the card's other link is the artist page in the aside below it, which carries no
                // g-blocklist-link class of its own
                val gigUrl = gigUrlFrom(card.select("a.g-blocklist-link").attr("abs:href"), eventsPath)
                val eventPage = Jsoup.parse(fetchPage(client, gigUrl.value, browserHeaders), gigUrl.value)

                Gig(
                    GigId(venue.id, gigUrl),
                    titleWithStatus(card),
                    dateOf(card),
                    // Why the page's image rather than the card's: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
                    posterUrlFrom(gigUrl, eventPage.select("meta[property=og:image]").attr("abs:content")),
                    descriptionFrom(eventPage, gigUrl, ::eventPageContent),
                )
            }
    }

    private val url = "https://bushhall.seetickets.com/search/all"
    private val eventsPath = "https://bushhall.seetickets.com/event/"

    // Accept-Encoding is deliberately absent - OkHttp sets it and decompresses the reply itself,
    // where asking here would hand back bytes nothing decodes.
    // Why the whole set: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
    private val browserHeaders = listOf(
        "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-GB,en;q=0.9",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "sec-ch-ua" to "\"Chromium\";v=\"126\", \"Not?A_Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"macOS\"",
    )

    // Why the listing is checked rather than walked: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
    private fun checkSinglePage(listing: Document) {
        val pagination = listing.select("nav.pagination").textOrNull()
            ?: error("No pagination on $url - it no longer says whether the listing is complete")
        check(pagination.trim() == "1 of 1") {
            "$url says \"$pagination\" rather than \"1 of 1\", so its gigs no longer all arrive in one page"
        }
    }

    // See Tickets says either in the same place, the button's slot, where "Find Tickets" would
    // otherwise be.
    // Why the marker is written into the title: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
    private fun titleWithStatus(card: Element): GigTitle {
        val title = card.select(".event-title").text()
        return titleFrom(
            when (card.select(".v2-price-status").text().trim().lowercase()) {
                "cancelled" -> "$title$cancelledSuffix"
                "sold out" -> "$title$soldOutSuffix"
                else -> title
            }
        )
    }

    // Each card prints its date twice, as "Saturday 05 September 2026" and again as "05/09/2026"
    // beside the door time. The first is read: it names its weekday and spells its month out, where
    // the second reads as a date in either order and so as a different month for any day under 13.
    private fun dateOf(card: Element): GigDate {
        val written = card.select(".ev-listing-date time").first()?.attr("datetime")
        check(!written.isNullOrBlank()) { "No date on a $venue card - the listing's date selector no longer matches it" }
        return GigDate(LocalDate.parse(written, dateFormat))
    }

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy", Locale.ENGLISH)

    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    internal fun eventPageContent(page: Document) =
        page.selectOrNull("div.g-grid-col.x9 > section.g-ui-box") { details ->
            details.select("section.g-event-narratives div.g-ui-box-content").text().trim()
        }
}
