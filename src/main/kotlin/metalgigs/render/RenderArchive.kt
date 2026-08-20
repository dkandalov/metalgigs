package metalgigs.render

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// every render is kept, and index.html is a copy of the most recent one - the same split as
// .image-cache/ and images/: the archive is the artifact of record, the published file is a copy.
// Keeping the copy rather than writing the html twice means index.html can't silently differ from
// the archived render the log entry names
fun archiveRender(html: String, renderedDir: File, indexFile: File, at: Instant): File {
    renderedDir.mkdirs()
    val archived = File(renderedDir, renderedFileName(at))
    archived.writeText(html)
    archived.copyTo(indexFile, overwrite = true)
    return archived
}

internal fun renderedFileName(at: Instant): String = "${renderedFileTimestamp.format(at)}.html"

// colons would be the natural ISO separator but make the file miserable to handle in a shell, so
// the time part uses dashes; still sorts chronologically as a plain string
private val renderedFileTimestamp =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC)
