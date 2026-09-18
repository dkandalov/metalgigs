package metalgigs.scrape.venues

import metalgigs.Venue
import metalgigs.scrape.fetchPage
import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.jsoup.Jsoup
import java.net.URLDecoder
import kotlin.text.Charsets.UTF_8
import kotlin.test.Test

// An experiment rather than a check on this project's behaviour: it reads both Squarespace venues
// live and reports, gig by gig, whether the poster in the event page's copy is the picture the
// listing card already carried. The measurement behind the poster rule in ADR 9 - a card's thumbnail
// is whatever the venue set as the event's featured image, which is as often a press shot as the
// night's artwork - rather than a claim made off one gig.
//
// It hits the real sites, one request per gig, which is what a daily scrape does anyway, so it runs
// only when SQUARESPACE_POSTERS is set - the same way DEV_FLYER gates the other experiment here.
class SquarespacePosterExperiment {

    @Test
    fun `how often a Squarespace event page's poster is not the listing card's picture`() {
        assumeTrue(System.getenv("SQUARESPACE_POSTERS") != null, "set SQUARESPACE_POSTERS=1 to run this experiment")

        report(theDome, "https://www.domelondon.co.uk/whatson")
        report(theBlackHeart, "https://www.ourblackheart.com/events")
    }

    private fun report(venue: Venue, url: String) {
        val source = SquarespaceEventsGigsSource(client, url, venue)
        val listing = Jsoup.parse(fetchPage(client, url), url)

        // the card's own thumbnail selector, read here rather than through the source, which no
        // longer says which of the two pictures it took
        val cards = listing.select("article.eventlist-event--upcoming").map { item ->
            val gigUrl = item.select(".eventlist-title-link").attr("abs:href")
            val thumbnail = item.select(".eventlist-column-thumbnail img").first()
                ?.let { it.attr("abs:src").ifBlank { it.attr("abs:data-image") } }
            val poster = source.eventPagePoster(Jsoup.parse(fetchPage(client, gigUrl), gigUrl))
            Triple(item.select(".eventlist-title-link").text(), thumbnail, poster)
        }

        // Squarespace serves the same upload from two paths - the card's dated one and the image
        // block's uuid one - and escapes a space as %2B in one and + in the other, so two urls
        // saying nothing alike can still be one picture. The file name is what tells them apart.
        val (withPageImage, withoutPageImage) = cards.partition { it.third != null }
        val (sameFile, differentFile) = withPageImage.partition { fileName(it.second) == fileName(it.third) }

        println("\n${venue.name}: ${cards.size} gig(s) listed at $url")
        println("  ${withPageImage.size} carry an image block in the copy, ${withoutPageImage.size} do not")
        println("  ${differentFile.size} would be published under a different picture")
        println("  ${sameFile.size} would keep the same picture at a new url")
        println("  ${cards.count { it.second == null }} have no thumbnail on the card at all")
        differentFile.forEach { (title, thumbnail, poster) ->
            println("    $title")
            println("      card: ${fileName(thumbnail)}")
            println("      page: ${fileName(poster)}")
        }
        withoutPageImage.forEach { (title, thumbnail, _) ->
            println("    no page image, keeping the card's ${fileName(thumbnail)} - $title")
        }
        cards.filter { it.second == null }.forEach { (title, _, poster) ->
            println("    no card thumbnail, published under the page's ${fileName(poster)} - $title")
        }
    }

    // One venue's two paths escape the same upload differently - a space reaching the card as %2B
    // and the image block as + - so the names are read back to what the promoter saved before they
    // are compared.
    private fun fileName(url: String?): String {
        val name = url?.substringBefore('?')?.substringAfterLast('/') ?: return "nothing"
        return URLDecoder.decode(name.replace("+", "%2B"), UTF_8).replace('+', ' ')
    }

    private val client: HttpHandler = OkHttp()
}
