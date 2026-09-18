package metalgigs

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// shrinks an image to what the page actually displays and re-encodes it as webp. Kept as a plain
// external process rather than a library because the JDK has no webp encoder at all, and the only
// pure-JVM options are native-code bindings that would need shipping per platform
fun convertToWebp(source: File, target: File) =
    runMagick("converting $source to webp", listOf(source.path, "-resize", RESIZE_GEOMETRY, "-strip", "-quality", "$WEBP_QUALITY", target.path))

// Why a flyer is enlarged before a model reads it: docs/adr/0011-the-devs-month-flyer-is-a-source-read-by-a-local-model.md
fun enlargeForReading(source: File, target: File) =
    runMagick("enlarging $source", listOf(source.path, "-resize", ENLARGE_GEOMETRY, target.path))

// identify prints a line for every image it could read and says a failed one only in its exit code,
// so what came back is read rather than that code - an unreadable image is one this map has no
// entry for.
// Why the page needs each image's proportions: docs/adr/0014-a-card-is-drawn-to-its-posters-own-shape.md
fun imageRatios(images: List<File>): Map<File, Double> {
    if (images.isEmpty()) return emptyMap()
    val (_, output) = magick("reading the size of ${images.size} image(s)", listOf("identify", "-format", "%i %w %h\n") + images.map { it.path })
    return output.lineSequence().mapNotNull(::ratioOf).toMap()
}

// identify echoes the path it was given, and any line that isn't one of ours - the message it
// prints for an image it couldn't open - doesn't end in two numbers.
private fun ratioOf(line: String): Pair<File, Double>? {
    val fields = line.trim().split(' ')
    if (fields.size < 3) return null
    val height = fields.last().toIntOrNull() ?: return null
    val width = fields[fields.size - 2].toIntOrNull() ?: return null
    if (width <= 0 || height <= 0) return null
    return File(fields.dropLast(2).joinToString(" ")) to width.toDouble() / height
}

private fun runMagick(what: String, arguments: List<String>) {
    val (exitValue, output) = magick(what, arguments)
    // judged on the exit code alone: several of these images convert perfectly well while
    // printing warnings (the PNGs with duplicate eXIf chunks, for one), so output isn't failure
    check(exitValue == 0) { "Failed $what (exit $exitValue): ${output.trim()}" }
}

private fun magick(what: String, arguments: List<String>): Pair<Int, String> {
    // magick writes little, but a pipe nobody drains can still deadlock a process, so both streams
    // go to a file that's read only once the process has exited
    val log = File.createTempFile("magick", ".log")
    try {
        val process = try {
            ProcessBuilder(listOf(magickBinary) + arguments).redirectErrorStream(true).redirectOutput(log).start()
        } catch (e: IOException) {
            error("Could not run \"$magickBinary\" - install ImageMagick or set MAGICK_BINARY to its path: ${e.message}")
        }

        if (!process.waitFor(CONVERT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Timed out after ${CONVERT_TIMEOUT_SECONDS}s $what")
        }
        return process.exitValue() to log.readText()
    } finally {
        log.delete()
    }
}

// resolved from the environment so a machine that keeps ImageMagick somewhere unusual, or a
// scheduled run with a bare PATH, can point at it without a code change
private val magickBinary = System.getenv("MAGICK_BINARY") ?: "magick"

// gig cards render at 260px at most - gigs.hbs caps the grid at 1100px and lays out
// minmax(220px, 1fr) columns, which works out at four 260px columns - and object-fit: cover crops
// each to a square. 768 covers that at 2x on desktop and on a phone in a single column, and is
// where measuring stopped paying: across all 246 published images, 768px webp totals 18MB against
// 11MB at 520px, both against 175MB of originals
private const val TARGET_SHORTER_SIDE = 768

// "^" sizes the *shorter* side to the target, which is the side that survives the square crop.
// ">" never enlarges, so a venue that only publishes a 200px thumbnail keeps its 200px rather than
// being blown up into a bigger file with no more detail in it
private const val RESIZE_GEOMETRY = "${TARGET_SHORTER_SIDE}x${TARGET_SHORTER_SIDE}^>"

// lossy webp. Not a free dial to turn up: ImageMagick switches to *lossless* webp at quality 100,
// which measured 5.6x larger than 80 here and byte-for-byte the same as -define webp:lossless=true.
// Set explicitly because leaving it off doesn't give ImageMagick's usual 92 - it gives libwebp's
// own default of 75, confirmed identical output to passing 75
private const val WEBP_QUALITY = 80

// The three flyers the extraction was fitted to came off Instagram's api at 1080x1440; the page
// serves 480x640, and at that size the model stops reading the second line of a two-line row.
// "^" sizes the shorter side to the target and "<" never shrinks, so a flyer already at 1080 or
// above is passed through untouched rather than resampled for nothing.
private const val ENLARGE_TARGET = 1080
private const val ENLARGE_GEOMETRY = "${ENLARGE_TARGET}x${ENLARGE_TARGET}^<"

private const val CONVERT_TIMEOUT_SECONDS = 60L
