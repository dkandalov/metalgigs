package metalgigs.scrape.venues

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.num
import com.ubertob.kondor.json.obj
import com.ubertob.kondor.json.str
import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

val britishAirwaysArc = Venue(VenueId("british-airways-arc"), "British Airways ARC")

// The listing has no category on it and carries a squash championship beside its gigs, so everything
// is taken and left to the classifier, as at The O2 Arena.
// Why the events endpoint rather than the page: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
class BritishAirwaysArcGigsSource(private val client: HttpHandler, private val joinedActs: JoinedActs) : GigsSource {
    override val venue = britishAirwaysArc

    override fun latestGigs(): List<Gig> = cardsListed().map { card ->
        val link = card.select("h3.artist-name a")
        val gigUrl = gigUrlFrom(link.attr("abs:href"), "https://www.britishairwaysarc.co.uk/event/")
        val listedAs = titleFrom(link.text())
        val strapline = card.selectFirst("p.tour-title")?.text()
        val eventPage = Jsoup.parse(fetchPage(client, gigUrl.value), gigUrl.value)

        Gig(
            GigId(venue.id, gigUrl),
            strapline?.let { billedTitle(it, listedAs) } ?: listedAs,
            startDateOf(card),
            posterUrlFrom(gigUrl, posterOn(eventPage)),
            descriptionFrom(eventPage, gigUrl, ::eventPageContent),
            strapline?.let { billedLineup(it) } ?: Lineup.none,
            status = null,
        )
    }

    private fun cardsListed(): List<Element> {
        val batches = generateSequence(batch(1)) { last ->
            if (last.pager.currentPage < last.pager.totalPages) batch(last.pager.currentPage + 1) else null
        }.take(maxPages).toList()
        val cards = batches.flatMap { Jsoup.parse(it.items, siteUrl).select("article.event-card") }

        val listed = batches.first().pager.totalItems
        check(cards.size == listed) { "Read ${cards.size} of the $listed events $venue says it lists - its paging may no longer be followed to the end" }
        return cards
    }

    private fun batch(page: Int): ArcEvents =
        JArcEvents.fromJson(fetchPage(client, "https://www.britishairwaysarc.co.uk/wp-json/dest/v1/events?page=$page&initial=0")).orThrow()

    // Only to bound a pathological site bug; the real stop is the pager's own last page.
    private val maxPages = 30

    // The cards carry absolute urls of their own; this only gives Jsoup a base to resolve against.
    private val siteUrl = "https://www.britishairwaysarc.co.uk/"

    // A run of days prints two times and no year - "1 Apr - 4 Apr" - each still dated in full in its
    // datetime. Only the start date is used, as at The O2.
    internal fun startDateOf(card: Element): GigDate =
        GigDate.parse(card.select(".card-event-date time").first()!!.attr("datetime"))

    // Why the page's image rather than the card's: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
    private fun posterOn(page: Document): String? = page.selectFirst("section.banner picture source")?.attr("abs:srcset")

    // The footer's app-download modal is a .wysiwyg too, the same line of it on every page.
    // Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    internal fun eventPageContent(page: Document) = page.select("main .wysiwyg").textOrNull()

    internal fun billedTitle(strapline: String, listedAs: GigTitle): GigTitle? = titleBilledBy(billIn(strapline), listedAs)

    internal fun billedLineup(strapline: String): Lineup = lineupBilledBy(billIn(strapline), joinedActs)

    // A strapline names the tour and brackets the bill after it: "FINAL DAMNATION 50 (WITH SUPPORT
    // FROM: THE SAINTS ’73-’78)".
    private fun billIn(strapline: String) = bracketed.find(strapline)?.groupValues?.get(1) ?: strapline

    private val bracketed = Regex("""\(([^()]+)\)\s*$""")
}

private object JArcEvents : JAny<ArcEvents>() {
    private val items by str(ArcEvents::items)
    private val pager by obj(JArcPager, ArcEvents::pager)

    override fun JsonNodeObject.deserializeOrThrow() = ArcEvents(+items, +pager)
}

private object JArcPager : JAny<ArcPager>() {
    private val totalItems by num(ArcPager::totalItems)
    private val currentPage by num(ArcPager::currentPage)
    private val totalPages by num(ArcPager::totalPages)

    override fun JsonNodeObject.deserializeOrThrow() = ArcPager(+totalItems, +currentPage, +totalPages)
}

// The cards arrive as one string of html rather than as events.
private data class ArcEvents(val items: String, val pager: ArcPager)

private data class ArcPager(val totalItems: Int, val currentPage: Int, val totalPages: Int)
