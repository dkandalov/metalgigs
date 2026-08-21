package metalgigs.scrape

import metalgigs.Gig
import metalgigs.GigDate
import metalgigs.GigId
import metalgigs.GigUrl
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Status.Companion.GONE
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Uri
import org.http4k.core.relative

// What a run's listing says about a gig the log holds and it no longer carries. A venue that edits a
// gig it has already published often writes it a new url - both Cart and Horses and dice.fm build
// one out of the title, so swapping a support act moves the gig - and a gig is identified by where
// it lives, so the log ends up holding one night twice and the page printing it twice.
//
// Only the run that first misses the old url can tell: by the next one the old gig is no longer
// current and there is nothing left to compare. So this is decided as the listing is read, and what
// it decides is recorded.
internal sealed interface MissingGig {
    // the venue says where the gig went, which is an answer rather than a guess
    data class MovedTo(val url: GigUrl) : MissingGig

    // the old page has been deleted, so the gig is not merely absent from a listing - but a gig
    // cancelled outright looks exactly like this, so a candidate still has to be found
    data object Gone : MissingGig

    // the page is still served, so whatever it is, it isn't a gig that has moved somewhere else
    data object Live : MissingGig
}

// A url the venue redirects is followed to its target and needs nothing else: the site is naming the
// gig's new home. A url it has deleted names nothing, so a gig on the same night whose title is
// mostly the same words is what stands in for it - the edits that move a url are small ones, an
// added support band or a settled festival name.
//
// Similarity alone would not do. Measured over the 1874 upcoming gigs in the log on 2026-08-21, the
// pairs sharing a venue and a night rank Union Chapel's matinee and evening sittings of one show
// (0.60) and The O2 Forum's single night beside its 4-day ticket (0.80) above one real move (Cart
// and Horses' 0.44) and level with another (Signature Brew's 0.60). No threshold tells those apart.
// What does is that the sittings and the ticket types are all still listed: only a gig the venue has
// stopped listing is a candidate at all, and only then does its old url get asked about itself.
//
// The threshold then only has to separate what remains. The moves scored 0.44, 0.60 and 0.86, and the
// nearest thing to a false pair - two Camden Fringe shows on one night at Union Chapel - scored 0.33.
internal fun replacementsIn(
    scraped: List<Gig>,
    previous: List<Gig>,
    from: GigDate,
    missingGigSays: (GigUrl) -> MissingGig,
): List<Pair<GigId, GigId>> {
    val listed = scraped.map { it.id }.toSet()
    val byUrl = scraped.associateBy { it.id.url }

    return previous.filter { it.date >= from && it.id !in listed }
        .mapNotNull { missing ->
            when (val says = missingGigSays(missing.id.url)) {
                is MissingGig.MovedTo -> byUrl[says.url]
                MissingGig.Gone -> scraped.filter { it.date == missing.date }
                    .map { it to titleSimilarity(it, missing) }
                    .filter { (_, similarity) -> similarity >= LEAST_SIMILAR_TITLE }
                    .maxByOrNull { (_, similarity) -> similarity }
                    ?.first
                MissingGig.Live -> null
            }?.let { replacement -> missing.id to replacement.id }
        }
}

// A title's words rather than its characters, so the edit that moved the url - a band added, a word
// dropped - counts once however long it is, and "LOLA (AUS) | London" reads as the same gig as
// "LOLA (AUS) + Lucky Hit | London". Short words are left out: every second title has an "at", a
// "the" or a "+" in it, and they say nothing about which gig it is.
private fun titleSimilarity(one: Gig, other: Gig): Double {
    val words = wordsIn(one.title.value)
    val otherWords = wordsIn(other.title.value)
    return if (words.isEmpty() || otherWords.isEmpty()) 0.0
    else words.intersect(otherWords).size.toDouble() / words.union(otherWords).size
}

private fun wordsIn(title: String) =
    title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()

private const val LEAST_SIMILAR_TITLE = 0.4

// Asked of the old url itself rather than of the listing that has stopped carrying it, so a venue
// that keeps the page up - a sold-out gig dropped from a "what's on", a second listing we have
// wrongly paired - answers for itself. A redirect is followed no further than its own Location: the
// point is where the venue says the gig went, not what is served there.
internal fun missingGigSays(client: HttpHandler, url: GigUrl): MissingGig {
    val response = client(Request(GET, url.value).header("User-Agent", browserUserAgent))
    return when {
        // resolved against the url asked about, because a Location may be relative and half of them
        // are: dice.fm answers with the whole url and Cart and Horses with "/news-offers-events/...",
        // which would match no gig at all if it were read as it stands
        response.status.redirection -> response.header("location")
            ?.let { MissingGig.MovedTo(GigUrl(Uri.of(url.value).relative(it).toString())) } ?: MissingGig.Live
        response.status == NOT_FOUND || response.status == GONE -> MissingGig.Gone
        else -> MissingGig.Live
    }
}

// Several venues answer a request without one with a 403, which would read as a page still being
// served - the same string the sources that need it send.
private const val browserUserAgent =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
