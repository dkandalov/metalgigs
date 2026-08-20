package metalgigs

import org.http4k.core.HttpHandler
import java.io.File
import java.security.MessageDigest

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

// the cache can miss - a gig scraped before the cache existed, or one whose download failed at
// scrape time - so publishing falls back to fetching.
//
// Publishing re-encodes rather than copies: venues serve whatever they happen to have, which here
// ranged from 200px thumbnails to 4096px posters and 7MB PNGs, none of it sized for a 260px card.
// The cache keeps the original, so this is only ever discarding pixels the page can't show anyway.
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
        val cached = downloadToCache(client, gig.posterUrl, cacheDir)
        publishedDir.mkdirs()
        convert(cached, published)
    }
    return published
}

// safe to remove precisely because the cache still holds their bytes if a later render needs them
fun unpublishedImageFiles(keptGigs: List<Gig>, publishedFiles: List<File>): List<File> {
    val expectedFileNames = keptGigs.map { publishedImageFileName(it) }.toSet()
    return publishedFiles.filter { it.name !in expectedFileNames }
}

fun downloadToCache(client: HttpHandler, imageUrl: PosterUrl, cacheDir: File): File {
    val file = cachedImageFile(cacheDir, imageUrl)
    if (!file.exists()) {
        cacheDir.mkdirs()
        file.writeBytes(fetchBytes(client, imageUrl.value, "image at $imageUrl"))
    }
    return file
}

fun publishedImageFileName(gig: Gig): String =
    "${gig.date}-${gig.id.venueId}-${shortHash(gig.posterUrl.value)}.webp"

private fun cachedImageFile(cacheDir: File, imageUrl: PosterUrl): File =
    File(cacheDir, "${shortHash(imageUrl.value)}.${imageUrlExtension(imageUrl.value)}")

// only the last path segment is looked at, because a url whose own file name has no extension - the
// Music Glue CDN names Windmill Brixton's posters after the event, extensionless - would otherwise
// take everything after the dot in the *host* as its extension, slashes and all, and the cache file
// named from that is a path whose directories don't exist
fun imageUrlExtension(url: String): String =
    url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "jpg")

private fun shortHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)

fun slug(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
