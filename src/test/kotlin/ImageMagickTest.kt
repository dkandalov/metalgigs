import org.junit.jupiter.api.Assumptions.assumeTrue
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFailsWith

// exercises the real ImageMagick, unlike ImageCacheTest which injects a stand-in. Skipped rather
// than failed where ImageMagick isn't installed, since only rendering needs it
class ImageMagickTest {

    private fun magickAvailable() =
        runCatching { ProcessBuilder("magick", "-version").start().waitFor() == 0 }.getOrDefault(false)

    private fun tempFile(suffix: String) =
        File.createTempFile("magick-test", suffix).apply { deleteOnExit() }

    // a plain gradient, which unlike a flat colour is actually worth compressing
    private fun writePng(width: Int, height: Int): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) {
            for (y in 0 until height) {
                image.setRGB(x, y, Color(x * 255 / width, y * 255 / height, 128).rgb)
            }
        }
        return tempFile(".png").also { ImageIO.write(image, "png", it) }
    }

    private fun dimensions(file: File): String {
        val process = ProcessBuilder("magick", "identify", "-format", "%wx%h", file.path)
            .redirectErrorStream(true).start()
        return process.inputStream.bufferedReader().readText().also { process.waitFor() }
    }

    @Test
    fun `shrinks a large image so its shorter side hits the target`() {
        assumeTrue(magickAvailable())
        val source = writePng(width = 2000, height = 1600)
        val target = tempFile(".webp")

        convertToWebp(source, target)

        // shorter side 1600 -> 768, the longer side following at the same ratio
        expectThat(dimensions(target)).isEqualTo("960x768")
        expectThat(target.length() < source.length()).isTrue()
    }

    // a venue that only publishes small images shouldn't have them inflated into bigger files with
    // no more detail in them
    @Test
    fun `leaves an image smaller than the target at its own size`() {
        assumeTrue(magickAvailable())
        val source = writePng(width = 300, height = 200)
        val target = tempFile(".webp")

        convertToWebp(source, target)

        expectThat(dimensions(target)).isEqualTo("300x200")
    }

    @Test
    fun `writes webp whatever the source format was`() {
        assumeTrue(magickAvailable())
        val target = tempFile(".webp")

        convertToWebp(writePng(width = 900, height = 900), target)

        // RIFF....WEBP header, so this is a real webp rather than a png with a webp name
        val header = target.readBytes().take(12).toByteArray().decodeToString()
        expectThat(header).contains("RIFF")
        expectThat(header).contains("WEBP")
    }

    @Test
    fun `fails naming the file when the source isn't an image`() {
        assumeTrue(magickAvailable())
        val notAnImage = tempFile(".jpg").apply { writeText("definitely not an image") }

        val error = assertFailsWith<IllegalStateException> { convertToWebp(notAnImage, tempFile(".webp")) }

        expectThat(error.message!!).contains(notAnImage.name)
    }
}
