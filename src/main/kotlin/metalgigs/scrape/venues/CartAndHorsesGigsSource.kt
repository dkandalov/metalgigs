package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

val cartAndHorses = Venue(VenueId("cart-and-horses"), "Cart & Horses")

class CartAndHorsesGigsSource(private val client: HttpHandler, private val year: Int) : GigsSource {
    override val venue = cartAndHorses

    override fun latestGigs(): List<Gig> {
        var currentYear = year
        var previousMonth: String? = null

        return Jsoup.parse(fetchPage(client, url), url)
            .select(".news-carousel__item")
            .filter { it.select(".news-carousel__date-wrap").isNotEmpty() }
            .map { item ->
                val month = item.select(".news-carousel__month").text()
                if (month == "Jan" && previousMonth != null && previousMonth != "Jan") currentYear++
                previousMonth = month
                val gigUrl = item.select(".news-carousel__link").attr("abs:href")

                Gig(
                    GigId(venue.id, gigUrl),
                    GigTitle(item.select(".news-carousel__link").text()),
                    GigDate(currentYear, monthsByShortName.getValue(month), item.select(".news-carousel__day").text().toInt()),
                    posterUrlFrom(gigUrl, item.select(".news-carousel__image").attr("abs:src")),
                    fetchDescription(client, gigUrl, ::eventPageContent),
                )
            }
    }

    private val url = "https://www.cartandhorses.london/news-offers-events/"

    // The Useyourlocal pub-website platform scopes an event page to nothing, so the whole page's
    // text carries the nav and footer - opening times, address, social links - into every gig.
    internal fun eventPageContent(page: Document) = page.select(".page_header, .page_content_inner").textOrNull()
}
