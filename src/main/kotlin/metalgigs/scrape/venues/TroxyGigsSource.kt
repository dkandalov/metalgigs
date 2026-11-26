package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val troxy = Venue(VenueId("troxy"), "Troxy")

// The listing carries a ballet, a book talk and a comedy night beside its gigs with no category on
// a card, so everything is taken and left to the classifier, as at The O2 Arena.
// Why one page is the whole listing: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
class TroxyGigsSource(private val client: HttpHandler) : GigsSource {
    override val venue = troxy

    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select("article.troxy-whatson-card")
            .map { card ->
                val gigUrl = gigUrlFrom(card.select("a").attr("abs:href"), "https://troxy.co.uk/event/")
                val eventPage = Jsoup.parse(fetchPage(client, gigUrl.value), gigUrl.value)

                Gig(
                    GigId(venue.id, gigUrl),
                    titleWithStatus(card),
                    dateOf(card),
                    posterUrlFrom(gigUrl, ticketmasterUpload(card.select(".troxy-whatson-card__upper__img img").attr("abs:src"))),
                    descriptionFrom(eventPage, gigUrl, ::eventPageContent),
                    Lineup.none,
                    statusOf(card),
                )
            }

    private val url = "https://troxy.co.uk/whats-on/"

    // Why the marker is written into the title: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
    private fun titleWithStatus(card: Element): GigTitle {
        val title = card.select(".troxy-whatson-card__lower__title").text()
        return titleFrom(
            when (statusOf(card)) {
                GigStatus.Cancelled -> "$title$cancelledSuffix"
                GigStatus.SoldOut -> "$title$soldOutSuffix"
                null -> title
            }
        )
    }

    // The pill said "onsale", "onsale-soon" or "soldout" on the 32 cards listed on 2026-11-26, none
    // of them cancelled, so what it says of a cancellation is not known and not guessed at.
    private fun statusOf(card: Element): GigStatus? =
        if (card.select(".troxy-status-pill").attr("data-status") == "soldout") GigStatus.SoldOut else null

    // A card's datetime is "13-12-26": day first, the year in two digits.
    // Why the card's date is the one read: docs/adr/0010-a-date-is-read-per-venue-and-a-missing-year-is-inferred.md
    private fun dateOf(card: Element): GigDate {
        val written = card.selectFirst("time")?.attr("datetime")
        check(!written.isNullOrBlank()) { "No date on a $venue card - the listing's date selector no longer matches it" }
        return GigDate(LocalDate.parse(written, dayFirst))
    }

    private val dayFirst = DateTimeFormatter.ofPattern("dd-MM-uu")

    // Every description ends on its ticket terms - "Please Note: Over 14s only. A max of 8 tickets
    // per person and per household applies" - and the link that unfolds the copy above them.
    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    internal fun eventPageContent(page: Document) =
        page.selectOrNull("#event-description") { block ->
            val copy = block.clone()
            copy.select("#read-more").remove()
            copy.select("p").filter { it.text().startsWith("Please Note:") }.forEach { it.remove() }
            copy.text()
        }
}

// Why the upload rather than the crop the card shows: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
internal fun ticketmasterUpload(url: String): String = url.replace(ticketmasterCrop, "$1_SOURCE")

private val ticketmasterCrop = Regex("""^(https://s1\.ticketm\.net/dam/.+/[0-9a-f-]{36})_[A-Z0-9_]+\.\w+$""")
