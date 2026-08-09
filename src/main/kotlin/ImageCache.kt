import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import java.io.File
import java.security.MessageDigest

private fun slug(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

private fun shortHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)

fun localImageFileName(gig: GigEvent): String {
    val extension = gig.imageUrl.substringBefore('?').substringAfterLast('.', "jpg")
    return "${gig.date()}-${slug(gig.venue)}-${shortHash(gig.imageUrl)}.$extension"
}

fun cacheImage(client: HttpHandler, gig: GigEvent, cacheDir: File): File {
    val file = File(cacheDir, localImageFileName(gig))
    if (!file.exists()) {
        cacheDir.mkdirs()
        val response = client(Request(GET, gig.imageUrl))
        check(response.status.successful) {
            "Failed to download image for \"${gig.title}\" at ${gig.venue} (${gig.imageUrl}): ${response.status}"
        }
        file.writeBytes(response.body.stream.readBytes())
    }
    return file
}

fun cacheGigImages(client: HttpHandler, gigs: List<GigEvent>, cacheDir: File) {
    gigs.filter { it.imageUrl.isNotBlank() }
        .forEach { gig -> cacheImage(client, gig, cacheDir) }
}
