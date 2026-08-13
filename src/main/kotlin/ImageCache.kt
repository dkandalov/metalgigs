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

// always .webp whatever the source was, since publishing re-encodes rather than copies. The name
// still hashes the source url, so a gig's identity here is unchanged
fun publishedImageFileName(gig: Gig): String =
    "${gig.date}-${slug(gig.id.venue)}-${shortHash(gig.imageUrl)}.webp"

fun downloadToCache(client: HttpHandler, imageUrl: String, cacheDir: File): File {
    val file = cachedImageFile(cacheDir, imageUrl)
    if (!file.exists()) {
        cacheDir.mkdirs()
        file.writeBytes(fetchBytes(client, imageUrl, "image at $imageUrl"))
    }
    return file
}

// the cache can miss - a gig scraped before the cache existed, or one whose download failed at
// scrape time - so publishing falls back to fetching.
//
// Publishing re-encodes rather than copies: venues serve whatever they happen to have, which here
// ranged from 200px thumbnails to 4096px posters and 7MB PNGs, none of it sized for a 260px card.
// The cache keeps the original, so this is only ever discarding pixels the page can't show anyway.
// convert is injectable so tests don't need ImageMagick to exercise the caching around it.
//
// Note an already-published file is left alone, and its name says nothing about the size or quality
// it was encoded at - so changing either of those in ImageMagick.kt won't re-encode what's already
// in images/. Delete the directory to force that; the cache means it costs no network.
fun publishGigImage(
    client: HttpHandler,
    gig: Gig,
    cacheDir: File,
    publishedDir: File,
    convert: (File, File) -> Unit = ::convertToWebp,
): File {
    val published = File(publishedDir, publishedImageFileName(gig))
    if (!published.exists()) {
        val cached = downloadToCache(client, gig.imageUrl, cacheDir)
        publishedDir.mkdirs()
        convert(cached, published)
    }
    return published
}

// safe to remove precisely because the cache still holds their bytes if a later render needs them
fun unpublishedImageFiles(renderedGigs: List<Gig>, publishedFiles: List<File>): List<File> {
    val expectedFileNames = renderedGigs.map { publishedImageFileName(it) }.toSet()
    return publishedFiles.filter { it.name !in expectedFileNames }
}
