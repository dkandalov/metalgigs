package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Month

val nambucca = Venue(VenueId("nambucca"), "Nambucca")

class NambuccaGigsSource(private val client: HttpHandler, private val joinedActs: JoinedActs, private val year: Int) : GigsSource {
    override val venue = nambucca

    // Why the year is counted: docs/adr/0010-a-date-is-read-per-venue-and-a-missing-year-is-inferred.md
    override fun latestGigs(): List<Gig> {
        var currentYear = year
        var previousMonth: Month? = null

        return Jsoup.parse(fetchPage(client, url), url)
            .select("ul.events_list li.single_event_list")
            .map { card ->
                val month = monthsByShortName.getValue(card.attr("data-evmonth"))
                if (previousMonth?.let { month < it } == true) currentYear++
                previousMonth = month
                val gigUrl = gigUrlFrom(card.select("> a").attr("abs:href"), eventsPath)
                val eventPage = Jsoup.parse(fetchPage(client, gigUrl.value), gigUrl.value)

                val listedAs = titleFrom(card.select(".event_list_title").text())

                Gig(
                    GigId(venue.id, gigUrl),
                    listedAs,
                    GigDate(currentYear, month, card.select(".eventlist_day").text().trim().toInt()),
                    posterUrlFrom(gigUrl, posterOn(eventPage)),
                    descriptionFrom(eventPage, gigUrl, ::eventPageContent),
                    lineupHeadedBy(listedAs, joinedActs),
                )
            }
    }

    private val url = "https://nambuccalondon.co.uk/upcoming-events/"
    private val eventsPath = "https://nambuccalondon.co.uk/js_events/"

    // Every card is marked no_event_image, and a page that embeds a video usually carries the
    // artwork beside it, so the img is asked for by name rather than as the column's first child.
    // Why the page's image, and a video's still: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
    internal fun posterOn(page: Document): String? {
        val artwork = page.select(".event_right img[itemprop=image]")
        if (artwork.isNotEmpty()) return artwork.widestImageUrl()
        val videoId = youtubeEmbed.find(page.select(".event_right iframe").attr("src"))?.groupValues?.get(1)
        return videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
    }

    private val youtubeEmbed = Regex("""youtube(?:-nocookie)?\.com/embed/([\w-]+)""")

    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    internal fun eventPageContent(page: Document) = page.select("[itemprop=description]").textOrNull()
}
