import org.http4k.client.OkHttp
import org.http4k.core.then
import org.http4k.filter.TrafficFilters
import org.http4k.traffic.ReadWriteCache
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AppTest {
    private val fixtures = File("src/test/resources/traffic")

    @Test
    fun `fetches news page (record on miss, replay on hit)`() {
        val storage = ReadWriteCache.Disk(fixtures.absolutePath)

        val client = TrafficFilters.ServeCachedFrom(storage)
            .then(TrafficFilters.RecordTo(storage))
            .then(OkHttp())

        val body = fetchPage(client, newsUrl)

        assertTrue(body.isNotBlank(), "expected non-empty body")
        assertTrue(body.contains("<html", ignoreCase = true), "expected HTML content")
    }
}
