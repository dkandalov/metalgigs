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
                    val eventPage = when (descriptionFrom) {
                        SquarespaceDescription.EventPage -> Jsoup.parse(fetchPage(client, gigUrl.value), gigUrl.value)
                        SquarespaceDescription.ListingExcerpt -> null
                    }
                    Gig(
                        GigId(venue.id, gigUrl),
                        GigTitle(titleLink.text()),
                        GigDate.parse(item.select("time.event-date").first()!!.attr("datetime")),
                        posterUrlFrom(gigUrl, eventPage?.let(::eventPagePoster) ?: item.squarespaceThumbnailUrl()),
                        when (eventPage) {
                            null -> GigDescription(
                                item.select(".eventlist-excerpt").textOrNull()
                                    ?: error("No excerpt on the listing for $gigUrl - the venue's listing selector may no longer match it")
                            )
                            else -> descriptionFrom(eventPage, gigUrl, ::eventPageContent)
                        },
                        Lineup.none,
                    )
                }
            }

    // Why the copy's image rather than the card's: docs/adr/0009-a-poster-is-taken-at-the-size-the-source-already-has.md
    internal fun eventPagePoster(page: Document): String? =
        page.select(".eventitem-column-content .sqs-block-image img").first()?.squarespaceImageUrl()

    private fun Element.squarespaceThumbnailUrl(): String? =
        select(".eventlist-column-thumbnail img").first()?.squarespaceImageUrl()

    // Squarespace's blocks sometimes resolve an image's `src` eagerly and sometimes leave it
    // lazy-loaded with only `data-image` set, depending on the site
    private fun Element.squarespaceImageUrl(): String? =
        attr("abs:src").ifBlank { attr("abs:data-image") }.ifBlank { null }

    // Why the copy is scoped, re-parsed and kept in lines: docs/adr/0007-a-description-is-the-gigs-own-copy.md
    internal fun eventPageContent(page: Document): String? {
        val column = page.clone().select(".eventitem-column-content")
        if (column.isEmpty()) return null
        column.select(".sqs-block-button").remove()
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
        gig.copy(title = billedTitle(gig.description, gig.title) ?: gig.title, lineup = billedLineup(gig.description, gig.title))
    }

    internal fun billedTitle(copy: GigDescription, listedAs: GigTitle): GigTitle? {
        val (opensOn, billed) = billIn(copy, listedAs) ?: return null
        val guests = billed.filterNot { !it.matches(featuring) && listedAs.value.contains(it, ignoreCase = true) }
        var joinedBy = " / "
        var billedInTitle = 0
        val title = buildString {
            append(opensOn)
            for (line in guests) {
                if (line.matches(featuring)) {
                    joinedBy = " ft. "
                    continue
                }
                if (billedInTitle == guestsInTitle) break
                append(joinedBy).append(line)
                joinedBy = " / "
                billedInTitle++
            }
        }
        if (billedInTitle == 0) return null
        return titleFrom(title)
    }

    // Every act the copy bills, where a title takes three of them. What the bill opens on is an act
    // unless the acts are billed as featured at it, or the heading it is taken from names them itself.
    internal fun billedLineup(copy: GigDescription, listedAs: GigTitle): Lineup {
        val (opensOn, billed) = billIn(copy, listedAs) ?: return Lineup.none
        val guests = billed.filterNot { it.matches(featuring) }
        if (guests.isEmpty()) return Lineup.none
        val aNight = billed.any { it.matches(featuring) } || guests.any { opensOn.contains(it, ignoreCase = true) }
        return lineupFrom(if (aNight) guests else listOf(opensOn) + guests)
    }

    private fun billIn(copy: GigDescription, listedAs: GigTitle): Pair<String, List<String>>? {
        val lines = copy.value.lines()
        val marker = lines.indexOfFirst { it.matches(guestsBilled) }
        val named = lines.indexOfFirst { readsLikeAnAct(it) && sameName(it, listedAs.value) }
        val (billedAs, from) = when {
            marker >= 0 -> lines.getOrNull(marker - 1) to marker + 1
            named >= 0 -> lines[named] to named + 1
            else -> return null
        }
        return headliner(listedAs, billedAs) to lines.drop(from).takeWhile { readsLikeAnAct(it) && !it.contains(unbooked) }
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

    private val unbooked = Regex("""\bTBA\W*$""", IGNORE_CASE)

    private val featuring = Regex("""(?:FT|FEAT|FEATURING)\.?""")

    private val guestsInTitle = 3
}

class TheBlackHeartGigsSource(client: HttpHandler) :
    GigsSource by WithLineupsOnDiceAt(
        "The Black Heart",
        WithBilledGuests(SquarespaceEventsGigsSource(client, url = "https://www.ourblackheart.com/events", venue = theBlackHeart)),
        client,
    )

val theDome = Venue(VenueId("dome"), "The Dome")

class DomeLondonGigsSource(client: HttpHandler, joinedActs: JoinedActs) :
    GigsSource by WithLineupsOnDiceAt(
        "The Dome",
        WithSupportsBilledInTheOpeningLine(SquarespaceEventsGigsSource(client, url = "https://www.domelondon.co.uk/whatson", venue = theDome), joinedActs),
        client,
    )

// The Dome's copy opens on who presents the gig and who plays it - "Action! presents URNE with special
// guests The Grey and Moon Reaper live at The Dome" - on 49 of the 55 gigs listed on 2026-11-15, the
// promoter's name sometimes on a line of its own above it.
internal class WithSupportsBilledInTheOpeningLine(private val source: GigsSource, private val joinedActs: JoinedActs) : GigsSource by source {
    override fun latestGigs(): List<Gig> = source.latestGigs().map { gig -> gig.copy(lineup = billedLineup(gig.description)) }

    internal fun billedLineup(copy: GigDescription): Lineup {
        val lines = copy.value.lines()
        val opening = if (lines.first().contains(promoterAlone)) lines.take(2).joinToString(" ") else lines.first()
        if (!opening.contains(theFormula)) return Lineup.none
        return supportsBilled.find(opening)?.let { lineupBilledBy(it.value.removeSuffix("."), joinedActs) } ?: Lineup.none
    }

    // What says the line is the venue's formula rather than the copy's first sentence.
    private val theFormula = Regex("""\bpresents?\b|\bat the dome\.?$""", IGNORE_CASE)
    private val promoterAlone = Regex("""\bpresents?\s*$""", IGNORE_CASE)
    private val supportsBilled = Regex("""\b(?:plus|with)\s+(?:very\s+)?(?:special\s+guests?|supports?)\b.*?(?=\s+live\b|$)""", IGNORE_CASE)
}

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
