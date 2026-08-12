import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// gig cards render at 260px at most - gigs.hbs caps the grid at 1100px and lays out
// minmax(220px, 1fr) columns, which works out at four 260px columns - and object-fit: cover crops
// each to a square. 768 covers that at 2x on desktop and on a phone in a single column, and is
// where measuring stopped paying: across all 246 published images, 768px webp totals 18MB against
// 11MB at 520px, both against 175MB of originals
private const val TARGET_SHORTER_SIDE = 768

// lossy webp. Not a free dial to turn up: ImageMagick switches to *lossless* webp at quality 100,
// which measured 5.6x larger than 80 here and byte-for-byte the same as -define webp:lossless=true.
// Set explicitly because leaving it off doesn't give ImageMagick's usual 92 - it gives libwebp's
// own default of 75, confirmed identical output to passing 75
private const val WEBP_QUALITY = 80

// "^" sizes the *shorter* side to the target, which is the side that survives the square crop.
// ">" never enlarges, so a venue that only publishes a 200px thumbnail keeps its 200px rather than
// being blown up into a bigger file with no more detail in it
private const val RESIZE_GEOMETRY = "${TARGET_SHORTER_SIDE}x${TARGET_SHORTER_SIDE}^>"

private const val CONVERT_TIMEOUT_SECONDS = 60L

// resolved from the environment so a machine that keeps ImageMagick somewhere unusual, or a
// scheduled run with a bare PATH, can point at it without a code change
private val magickBinary = System.getenv("MAGICK_BINARY") ?: "magick"

// shrinks an image to what the page actually displays and re-encodes it as webp. Kept as a plain
// external process rather than a library because the JDK has no webp encoder at all, and the only
// pure-JVM options are native-code bindings that would need shipping per platform
fun convertToWebp(source: File, target: File) {
    // magick writes little, but a pipe nobody drains can still deadlock a process, so both streams
    // go to a file that's read only once the process has exited
    val log = File.createTempFile("magick", ".log")
    try {
        val process = try {
            ProcessBuilder(
                magickBinary,
                source.path,
                "-resize", RESIZE_GEOMETRY,
                "-strip",
                "-quality", WEBP_QUALITY.toString(),
                target.path,
            ).redirectErrorStream(true).redirectOutput(log).start()
        } catch (e: IOException) {
            error("Could not run \"$magickBinary\" - install ImageMagick or set MAGICK_BINARY to its path: ${e.message}")
        }

        if (!process.waitFor(CONVERT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Timed out after ${CONVERT_TIMEOUT_SECONDS}s converting $source")
        }
        // judged on the exit code alone: several of these images convert perfectly well while
        // printing warnings (the PNGs with duplicate eXIf chunks, for one), so output isn't failure
        check(process.exitValue() == 0) {
            "Failed to convert $source to webp (exit ${process.exitValue()}): ${log.readText().trim()}"
        }
    } finally {
        log.delete()
    }
}
