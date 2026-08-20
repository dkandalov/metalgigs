package metalgigs

import metalgigs.scrape.theUnderworld
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ImageCacheTest {

    private fun tempDir() = File.createTempFile("images", "").apply { delete(); deleteOnExit() }

    // the real converter runs ImageMagick over a genuine image; these tests are about the caching
    // and naming around it, and their "images" are a few bytes of text, so they just copy
    private val copyingConvert: (File, File) -> Unit = { source, target -> source.copyTo(target, overwrite = true) }

    private fun gig(day: Int = 8, venue: Venue = theUnderworld, posterUrl: String = "https://example.com/images/some-gig.jpg?w=200") =
        Gig(GigId(venue.id, "https://example.com/gigs/some-gig"), GigTitle("Some Gig"), LocalDate.of(2026, 8, day), PosterUrl(posterUrl), GigDescription(""))

    @Test
    fun `caches a downloaded image and skips re-downloading on a cache hit`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("fake-image-bytes") }
        val cacheDir = tempDir()

        val first = downloadToCache(fakeClient, gig().posterUrl, cacheDir)
        val second = downloadToCache(fakeClient, gig().posterUrl, cacheDir)

        expectThat(requestCount).isEqualTo(1)
        expectThat(first).isEqualTo(second)
        expectThat(first.readText()).isEqualTo("fake-image-bytes")
        expectThat(first.extension).isEqualTo("jpg")
    }

    // Windmill Brixton's CDN names a poster after the event, with no extension at all - and the
    // dots in the host are no business of the extension's
    @Test
    fun `caches an image whose url has no file extension, without taking one from the host`() {
        val fakeClient: HttpHandler = { Response(OK).body("fake-image-bytes") }
        val cacheDir = tempDir()
        val extensionless = "https://musicglue-images-prod.global.ssl.fastly.net/windmill-brixton/event/2026-11-19-grommet-the-windmill?u=aHR0cHM&v=2"

        val cached = downloadToCache(fakeClient, PosterUrl(extensionless), cacheDir)

        expectThat(cached.extension).isEqualTo("jpg")
        expectThat(cached.parentFile).isEqualTo(cacheDir)
        expectThat(cached.readText()).isEqualTo("fake-image-bytes")
    }

    @Test
    fun `caches one copy of an image shared by several gigs, since it is keyed by url`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("fake-image-bytes") }
        val cacheDir = tempDir()
        val sharedPoster = "https://example.com/images/monthly-poster.jpg"

        // the same poster advertising gigs on different days, as a venue's monthly flyer does
        downloadToCache(fakeClient, gig(day = 8, posterUrl = sharedPoster).posterUrl, cacheDir)
        downloadToCache(fakeClient, gig(day = 9, posterUrl = sharedPoster).posterUrl, cacheDir)

        expectThat(requestCount).isEqualTo(1)
        expectThat(cacheDir.listFiles()!!.size).isEqualTo(1)
    }

    @Test
    fun `publishes from the cache without hitting the network`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("fake-image-bytes") }
        val cacheDir = tempDir()
        val publishedDir = tempDir()
        downloadToCache(fakeClient, gig().posterUrl, cacheDir)
        expectThat(requestCount).isEqualTo(1)

        val published = publishGigImage(fakeClient, gig(), cacheDir, publishedDir, copyingConvert)

        // still 1: publishing copied the cached bytes rather than re-fetching them
        expectThat(requestCount).isEqualTo(1)
        expectThat(published.name).isEqualTo("2026-08-08-underworld-1af7931d.webp")
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
            publishGigImage(fakeClient, gig(posterUrl = "https://example.com/images/broken.jpg"), tempDir(), tempDir(), copyingConvert)
        }

        expectThat(error.message!!.contains("https://example.com/images/broken.jpg")).isTrue()
    }

    @Test
    fun `finds published files that no gig claims any more`() {
        val kept = gig(day = 8)
        val keptFile = File(publishedImageFileName(kept))
        val staleFile = File("2026-08-09-underworld-deadbeef.webp")

        val unpublished = unpublishedImageFiles(listOf(kept), listOf(keptFile, staleFile))

        expectThat(unpublished).isEqualTo(listOf(staleFile))
    }

    // a gig whose date has passed is off the page but still in the log, and its image stays put
    @Test
    fun `keeps the image of a gig that has dropped off the page`() {
        val past = gig(day = 8)
        val upcoming = gig(day = 20, posterUrl = "https://example.com/images/upcoming.jpg")
        val pastFile = File(publishedImageFileName(past))
        val upcomingFile = File(publishedImageFileName(upcoming))

        val unpublished = unpublishedImageFiles(listOf(past, upcoming), listOf(pastFile, upcomingFile))

        expectThat(unpublished).isEqualTo(emptyList())
    }
}
