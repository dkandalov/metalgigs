import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ImageCacheTest {

    @Test
    fun `caches downloaded images and skips re-downloading on cache hit`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("fake-image-bytes") }
        val cacheDir = File.createTempFile("images", "").apply { delete(); deleteOnExit() }
        val gig = GigEvent(
            title = "Some Gig",
            venue = "Some Venue",
            year = 2026,
            month = "Aug",
            day = "08",
            url = "https://example.com/gigs/some-gig",
            imageUrl = "https://example.com/images/some-gig.jpg?w=200",
        )

        val first = cacheImage(fakeClient, gig, cacheDir)
        val second = cacheImage(fakeClient, gig, cacheDir)

        expectThat(requestCount).isEqualTo(1)
        expectThat(first).isEqualTo(second)
        expectThat(first.readText()).isEqualTo("fake-image-bytes")
        expectThat(first.name).isEqualTo("2026-08-08-some-venue-1af7931d.jpg")
        expectThat(first.extension).isEqualTo("jpg")
    }

    @Test
    fun `fails fast with gig identity when image download fails`() {
        val fakeClient: HttpHandler = { Response(NOT_FOUND) }
        val cacheDir = File.createTempFile("images", "").apply { delete(); deleteOnExit() }
        val gig = GigEvent(
            title = "Broken Image Gig",
            venue = "Some Venue",
            year = 2026,
            month = "Aug",
            day = "08",
            url = "https://example.com/gigs/broken",
            imageUrl = "https://example.com/images/broken.jpg",
        )

        val error = assertFailsWith<IllegalStateException> { cacheImage(fakeClient, gig, cacheDir) }

        expectThat(error.message!!.contains("Broken Image Gig")).isTrue()
        expectThat(error.message!!.contains("Some Venue")).isTrue()
        expectThat(error.message!!.contains("https://example.com/images/broken.jpg")).isTrue()
    }
}
