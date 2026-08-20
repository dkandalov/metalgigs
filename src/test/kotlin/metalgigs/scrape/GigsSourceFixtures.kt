package metalgigs.scrape

import metalgigs.Gig
import metalgigs.GigDescription
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue

internal fun assertScrapesGigs(source: GigsSource, size: Int, first: Gig, last: Gig, urlPrefix: String): List<Gig> {
    val events = source.latestGigs()
    events.forEach { println(it) }

    expectThat(events).hasSize(size)
    // a description is its whole event page's text, so the per-venue expectations mask it rather
    // than carrying thousands of characters each - what's extracted from a page has its own tests
    expectThat(events.first().copy(description = GigDescription(""))).isEqualTo(first)
    expectThat(events.last().copy(description = GigDescription(""))).isEqualTo(last)
    expectThat(events.all { it.id.url.startsWith(urlPrefix) }).isTrue()
    expectThat(events.all { it.id.venueId == first.id.venueId }).isTrue()

    return events
}

// Each source parses its own event pages, so these go straight at that parsing - no listing page
// to scrape first, and no http.
internal val noHttp: HttpHandler = { request -> error("unexpected http request: ${request.uri}") }

internal fun pageOf(html: String) = Jsoup.parse(html, "https://example.com/gig")
