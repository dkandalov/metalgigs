import org.http4k.client.OkHttp
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.then
import org.http4k.filter.TrafficFilters
import org.http4k.traffic.ReadWriteCache
import java.io.File

private val fixtures = File("src/test/resources/traffic")

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

fun cachedClient(): HttpHandler = TrafficFilters.ServeCachedFrom(cache).then(
    if (System.getenv("RECORD_TRAFFIC") == null) noLiveRequests
    else TrafficFilters.RecordTo(cache).then(redactSecretsFilter).then(OkHttp()),
)
