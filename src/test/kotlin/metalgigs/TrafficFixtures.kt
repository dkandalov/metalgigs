package metalgigs

import org.http4k.client.OkHttp
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.filter.TrafficFilters
import org.http4k.traffic.ReadWriteCache
import java.io.File

private val fixtures = File("src/test/resources/traffic")
private val sharedEventPages = File("src/test/resources/traffic-event-pages")

// pages we scrape sometimes embed the site's own third-party API keys (maps, analytics, captcha)
// in inline JS; scrub those before they're written to disk as recorded test fixtures. Covers both
// the JS assignment form (window.MAPBOX_API_KEY = "...") and the JSON/config form
// ("maps_api_key":"..."), since sites embed them either way
private val secretPattern = Regex("""("?\w*(?:API_KEY|SECRET|TOKEN)\w*"?\s*[:=]\s*)(['"])[^'"]*\2""", RegexOption.IGNORE_CASE)

private fun redactSecrets(body: String): String =
    secretPattern.replace(body) { "${it.groupValues[1]}${it.groupValues[2]}REDACTED${it.groupValues[2]}" }

private val redactSecretsFilter = Filter { next ->
    { request -> next(request).let { it.body(redactSecrets(it.bodyString())) } }
}

private val cache = ReadWriteCache.Disk(fixtures.absolutePath)

private val noLiveRequests: HttpHandler =
    { request -> error("No recorded traffic for ${request.uri} - re-run with RECORD_TRAFFIC=1 to record it") }

// A gig's description comes from its own event page, so a venue's listing of 40 gigs means 40 more
// pages - and recording each of them took these fixtures from 12MB to 150MB across some 1500 pages
// that no test reads. What a source extracts from a page is tested against inline markup, and the
// per-venue tests mask descriptions rather than asserting them, so all these pages have to do is
// give that venue's selectors something to match. One recorded page per host does that.
//
// Only urls the exact cache misses land here, so a listing - recorded under its own url - is never
// answered with it. A host with no sample yet still goes live under RECORD_TRAFFIC, which is how a
// newly added venue records one. Restricted to GET because the listing calls some sources make are
// POSTs to their site's own api, and standing an event page in for one of those hides the miss:
// it parses as a page with none of the venue's listing markup on it, so the source reads it as a
// listing that has run out rather than as an unrecorded call.
private val serveSharedEventPage = Filter { next ->
    { request ->
        val shared = File(sharedEventPages, "${request.uri.host}.html")
        if (request.method == GET && shared.exists()) Response(OK).body(shared.readText()) else next(request)
    }
}

fun cachedClient(): HttpHandler = TrafficFilters.ServeCachedFrom(cache)
    .then(serveSharedEventPage)
    .then(
        if (System.getenv("RECORD_TRAFFIC") == null) noLiveRequests
        // FollowRedirects to match the client scrapeGigs builds: without it a venue that links its
        // gigs by shortlink (Paper Dress Vintage's ?p= urls) records empty bodies rather than pages
        else TrafficFilters.RecordTo(cache).then(redactSecretsFilter).then(ClientFilters.FollowRedirects()).then(OkHttp()),
    )
