import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import java.io.File
import java.security.MessageDigest

fun localImageFileName(url: String): String {
    val withoutQuery = url.substringBefore('?')
    val extension = withoutQuery.substringAfterLast('.', "jpg")
    val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    return "$hash.$extension"
}

fun cacheImage(client: HttpHandler, url: String, cacheDir: File): File {
    val file = File(cacheDir, localImageFileName(url))
    if (!file.exists()) {
        cacheDir.mkdirs()
        val response = client(Request(GET, url))
        check(response.status.successful) { "Failed to download image $url: ${response.status}" }
        file.writeBytes(response.body.stream.readBytes())
    }
    return file
}

fun cacheGigImages(client: HttpHandler, gigs: List<GigEvent>, cacheDir: File) {
    gigs.map { it.imageUrl }
        .filter { it.isNotBlank() }
        .distinct()
        .forEach { url -> cacheImage(client, url, cacheDir) }
}
