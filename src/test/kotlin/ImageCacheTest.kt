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

    private fun tempDir() = File.createTempFile("images", "").apply { delete(); deleteOnExit() }

    // the real converter runs ImageMagick over a genuine image; these tests are about the caching
    // and naming around it, and their "images" are a few bytes of text, so they just copy
    private val copyingConvert: (File, File) -> Unit = { source, target -> source.copyTo(target, overwrite = true) }

    private fun gig(day: String = "08", venue: String = "Some Venue", imageUrl: String = "https://example.com/images/some-gig.jpg?w=200") =
        Gig(id = GigId(venue, "https://example.com/gigs/some-gig"), title = "Some Gig", year = 2026, month = "Aug", day = day, imageUrl = imageUrl)

    @Test
    fun `caches a downloaded image and skips re-downloading on a cache hit`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("fake-image-bytes") }
        val cacheDir = tempDir()

        val first = downloadToCache(fakeClient, gig().imageUrl, cacheDir)
        val second = downloadToCache(fakeClient, gig().imageUrl, cacheDir)

        expectThat(requestCount).isEqualTo(1)
        expectThat(first).isEqualTo(second)
        expectThat(first.readText()).isEqualTo("fake-image-bytes")
        expectThat(first.extension).isEqualTo("jpg")
    }

    @Test
    fun `caches one copy of an image shared by several gigs, since it is keyed by url`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("fake-image-bytes") }
        val cacheDir = tempDir()
        val sharedPoster = "https://example.com/images/monthly-poster.jpg"

        // the same poster advertising gigs on different days, as a venue's monthly flyer does
        downloadToCache(fakeClient, gig(day = "08", imageUrl = sharedPoster).imageUrl, cacheDir)
        downloadToCache(fakeClient, gig(day = "09", imageUrl = sharedPoster).imageUrl, cacheDir)

        expectThat(requestCount).isEqualTo(1)
        expectThat(cacheDir.listFiles()!!.size).isEqualTo(1)
    }

    @Test
    fun `publishes from the cache without hitting the network`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("fake-image-bytes") }
        val cacheDir = tempDir()
        val publishedDir = tempDir()
        downloadToCache(fakeClient, gig().imageUrl, cacheDir)
        expectThat(requestCount).isEqualTo(1)

        val published = publishGigImage(fakeClient, gig(), cacheDir, publishedDir, copyingConvert)

        // still 1: publishing copied the cached bytes rather than re-fetching them
        expectThat(requestCount).isEqualTo(1)
        expectThat(published.name).isEqualTo("2026-08-08-some-venue-1af7931d.webp")
        expectThat(published.readText()).isEqualTo("fake-image-bytes")
    }

    @Test
    fun `downloads when publishing a gig the cache doesn't have`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("fake-image-bytes") }

        val published = publishGigImage(fakeClient, gig(), tempDir(), tempDir(), copyingConvert)

        expectThat(requestCount).isEqualTo(1)
        expectThat(published.readText()).isEqualTo("fake-image-bytes")
    }

    @Test
    fun `fails with the gig's identity when its image can't be downloaded`() {
        val fakeClient: HttpHandler = { Response(NOT_FOUND) }

        val error = assertFailsWith<IllegalStateException> {
            publishGigImage(fakeClient, gig(imageUrl = "https://example.com/images/broken.jpg"), tempDir(), tempDir(), copyingConvert)
        }

        expectThat(error.message!!.contains("https://example.com/images/broken.jpg")).isTrue()
    }

    @Test
    fun `finds published files that the rendered page no longer references`() {
        val rendered = gig(day = "08")
        val keptFile = File(publishedImageFileName(rendered))
        val staleFile = File("2026-08-09-some-venue-deadbeef.webp")

        val unpublished = unpublishedImageFiles(renderedGigs = listOf(rendered), publishedFiles = listOf(keptFile, staleFile))

        expectThat(unpublished).isEqualTo(listOf(staleFile))
    }
}
