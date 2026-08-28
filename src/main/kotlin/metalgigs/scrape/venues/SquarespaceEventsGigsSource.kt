package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import kotlin.text.RegexOption.COMMENTS
import kotlin.text.RegexOption.IGNORE_CASE

// most Squarespace venues write a gig's blurb on its own event page, but The Fiddler's Elbow leaves
// every one of those empty and puts the whole thing in the listing's excerpt instead - which also
// means one request for the listing rather than one more per gig
internal enum class SquarespaceDescription { EventPage, ListingExcerpt }

// shared by every Squarespace "Events List" venue page
internal class SquarespaceEventsGigsSource(
    private val client: HttpHandler,
    private val url: String,
    override val venue: Venue,
    private val descriptionFrom: SquarespaceDescription = SquarespaceDescription.EventPage,
    private val skippableGigs: Set<GigUrl> = emptySet(),
) : GigsSource {
    override fun latestGigs(): List<Gig> =
        Jsoup.parse(fetchPage(client, url), url)
            .select("article.eventlist-event--upcoming")
            .mapNotNull { item ->
                val titleLink = item.select(".eventlist-title-link")
                // a Squarespace events list hangs each event off the listing page's own path, so
                // The Black Heart lists at /events and its gigs sit at /events/2026/9/30/morag-tong
                val gigUrl = gigUrlFrom(titleLink.attr("abs:href"), "$url/")
                gigOrSkipped(gigUrl, skippableGigs) {
                    Gig(
                        GigId(venue.id, gigUrl),
                        GigTitle(titleLink.text()),
                        GigDate.parse(item.select("time.event-date").first()!!.attr("datetime")),
                        posterUrlFrom(gigUrl, item.squarespaceThumbnailUrl()),
                        when (descriptionFrom) {
                            SquarespaceDescription.EventPage -> fetchDescription(client, gigUrl, ::eventPageContent)
                            SquarespaceDescription.ListingExcerpt -> GigDescription(
                                item.select(".eventlist-excerpt").textOrNull()
                                    ?: error("No excerpt on the listing for $gigUrl - the venue's listing selector may no longer match it")
                            )
                        },
                    )
                }
            }

    // Squarespace's "Events List" block sometimes resolves the thumbnail's `src` eagerly and sometimes
    // leaves it lazy-loaded with only `data-image` set, depending on the site
    private fun Element.squarespaceThumbnailUrl(): String {
        val img = select(".eventlist-column-thumbnail img")
        return img.attr("abs:src").ifBlank { img.attr("abs:data-image") }
    }

    // Why the copy is scoped, re-parsed and kept in lines: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    internal fun eventPageContent(page: Document): String? {
        val column = page.clone().select(".eventitem-column-content")
        if (column.isEmpty()) return null
        column.select("br, p, div, h1, h2, h3, h4, li").before(lineMark)
        return Jsoup.parse(column.text()).text()
            .split(lineMark)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    // Jsoup flattens a block boundary to a space, and text() would do the same to a newline written
    // in its place, so the boundaries are marked with a character no promoter types and cut after.
    private val lineMark = "\u241E"
}

val theBlackHeart = Venue(VenueId("black-heart"), "The Black Heart")

// Why a title is composed from the bill: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
internal class WithBilledGuests(private val source: GigsSource) : GigsSource by source {
    override fun latestGigs(): List<Gig> = source.latestGigs().map { gig ->
        gig.copy(title = billedTitle(gig.description, gig.title) ?: gig.title)
    }

    internal fun billedTitle(copy: GigDescription, listedAs: GigTitle): GigTitle? {
        val lines = copy.value.lines()
        val marker = lines.indexOfFirst { it.matches(guestsBilled) }
        val named = lines.indexOfFirst { readsLikeAnAct(it) && sameName(it, listedAs.value) }
        val (billedAs, from) = when {
            marker >= 0 -> lines.getOrNull(marker - 1) to marker + 1
            named >= 0 -> lines[named] to named + 1
            else -> return null
        }
        val guests = lines.drop(from).takeWhile(::readsLikeAnAct)
            .filterNot { listedAs.value.contains(it, ignoreCase = true) }
            .take(guestsInTitle)
        if (guests.isEmpty()) return null
        return titleFrom("${headliner(listedAs, billedAs)} / ${guests.joinToString(" / ")}")
    }

    private fun headliner(listedAs: GigTitle, billedAs: String?): String {
        if (billedAs == null || !sameName(billedAs, listedAs.value)) return listedAs.value
        return if (diacritics(billedAs) >= diacritics(listedAs.value)) billedAs else listedAs.value
    }

    private fun sameName(one: String, other: String) = plain(one) == plain(other)

    private fun plain(name: String) = combiningMark.replace(decomposed(name), "").lowercase()

    private fun diacritics(name: String) = combiningMark.findAll(decomposed(name)).count()

    private fun decomposed(name: String) = Normalizer.normalize(name, Normalizer.Form.NFD)

    private val combiningMark = Regex("""\p{Mn}""")

    private fun readsLikeAnAct(line: String) = line.any(Char::isLetter) && line.none(Char::isLowerCase)

    private val guestsBilled = Regex(
        """
        ^
        (?: plus \s+ (?: special \s+ )? guests?
          | plus \s+ support
          | with
        )
        \s* [.…!:]* $
        """,
        setOf(IGNORE_CASE, COMMENTS),
    )

    private val guestsInTitle = 3
}

class TheBlackHeartGigsSource(client: HttpHandler) :
    GigsSource by WithBilledGuests(
        SquarespaceEventsGigsSource(client, url = "https://www.ourblackheart.com/events", venue = theBlackHeart),
    )

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
        skippableGigs = setOf(
            GigUrl("https://www.thefiddlerselbow.co.uk/whos-playing/s-for-sierra-ep-launch-party1992026"),
        ),
    )
