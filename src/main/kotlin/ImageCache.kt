import org.http4k.core.HttpHandler
import java.io.File
import java.security.MessageDigest

fun slug(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

private fun shortHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)

fun imageUrlExtension(url: String): String = url.substringBefore('?').substringAfterLast('.', "jpg")

// images are held in two places, for different reasons:
//
// - the download cache is keyed by image url alone, holds every gig's image whatever its genre,
//   and isn't committed. It's filled at scrape time, while the urls are still fresh - some expire
//   (the Facebook CDN ones carry an expiry parameter), so waiting until a gig is classified and
//   rendered can be too late. Keying by url also means one poster shared by a month of gigs is
//   stored once rather than per gig.
//
// - the published directory is keyed by gig, holds only what the rendered page references, and is
//   committed. It's built by copying out of the cache, so pruning it costs nothing to undo.
fun cachedImageFile(cacheDir: File, imageUrl: String): File =
    File(cacheDir, "${shortHash(imageUrl)}.${imageUrlExtension(imageUrl)}")

fun publishedImageFileName(gig: GigEvent): String =
    "${gig.date()}-${slug(gig.id.venue)}-${shortHash(gig.imageUrl)}.${imageUrlExtension(gig.imageUrl)}"

fun downloadToCache(client: HttpHandler, imageUrl: String, cacheDir: File): File {
    val file = cachedImageFile(cacheDir, imageUrl)
    if (!file.exists()) {
        cacheDir.mkdirs()
        file.writeBytes(fetchBytes(client, imageUrl, "image at $imageUrl"))
    }
    return file
}

// the cache can miss - a gig scraped before the cache existed, or one whose download failed at
// scrape time - so publishing falls back to fetching
fun publishGigImage(client: HttpHandler, gig: GigEvent, cacheDir: File, publishedDir: File): File {
    val published = File(publishedDir, publishedImageFileName(gig))
    if (!published.exists()) {
        val cached = downloadToCache(client, gig.imageUrl, cacheDir)
        publishedDir.mkdirs()
        cached.copyTo(published, overwrite = true)
    }
    return published
}

// safe to remove precisely because the cache still holds their bytes if a later render needs them
fun unpublishedImageFiles(renderedGigs: List<GigEvent>, publishedFiles: List<File>): List<File> {
    val expectedFileNames = renderedGigs.map { publishedImageFileName(it) }.toSet()
    return publishedFiles.filter { it.name !in expectedFileNames }
}
