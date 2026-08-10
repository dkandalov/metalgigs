import org.http4k.client.OkHttp
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.then
import org.http4k.filter.TrafficFilters
import org.http4k.traffic.ReadWriteCache
import java.io.File

private val fixtures = File("src/test/resources/traffic")

// pages we scrape sometimes embed the site's own third-party API keys (maps, analytics, etc.)
// in inline JS; scrub those before they're written to disk as recorded test fixtures
private val secretPattern = Regex("""(\w*(?:API_KEY|SECRET|TOKEN)\w*\s*=\s*)(['"])[^'"]*\2""", RegexOption.IGNORE_CASE)

private fun redactSecrets(body: String): String =
    secretPattern.replace(body) { "${it.groupValues[1]}${it.groupValues[2]}REDACTED${it.groupValues[2]}" }

private val redactSecretsFilter = Filter { next ->
    { request -> next(request).let { it.body(redactSecrets(it.bodyString())) } }
}

fun cachedClient(): HttpHandler = TrafficFilters.ServeCachedFrom(ReadWriteCache.Disk(fixtures.absolutePath))
    .then(TrafficFilters.RecordTo(ReadWriteCache.Disk(fixtures.absolutePath)))
    .then(redactSecretsFilter)
    .then(OkHttp())
