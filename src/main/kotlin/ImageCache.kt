import org.http4k.core.HttpHandler
import java.io.File
import java.security.MessageDigest

fun slug(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

private fun shortHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)

// the extension of the URL's own path, ignoring any query string; used both as the local cache
// file's extension and (elsewhere) to infer the image's mime type
fun imageUrlExtension(url: String): String = url.substringBefore('?').substringAfterLast('.', "jpg")

fun localImageFileName(gig: GigEvent): String =
    "${gig.date()}-${slug(gig.venue)}-${shortHash(gig.imageUrl)}.${imageUrlExtension(gig.imageUrl)}"

fun cacheImage(client: HttpHandler, gig: GigEvent, cacheDir: File): File {
    val file = File(cacheDir, localImageFileName(gig))
    if (!file.exists()) {
        cacheDir.mkdirs()
        file.writeBytes(fetchBytes(client, gig.imageUrl, "image for \"${gig.title}\" at ${gig.venue} (${gig.imageUrl})"))
    }
    return file
}

fun cacheGigImages(client: HttpHandler, gigs: List<GigEvent>, cacheDir: File) {
    gigs.filter { it.imageUrl.isNotBlank() }
        .forEach { gig -> cacheImage(client, gig, cacheDir) }
}

// cached image files that don't belong to any of the given (currently Metal-classified) gigs -
// e.g. left behind by a gig that was later reclassified away from Metal
fun orphanedImageFiles(metalGigs: List<GigEvent>, imageFiles: List<File>): List<File> {
    val expectedFileNames = metalGigs.map { localImageFileName(it) }.toSet()
    return imageFiles.filter { it.name !in expectedFileNames }
}
