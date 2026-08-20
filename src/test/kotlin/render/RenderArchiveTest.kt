import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.io.File
import java.time.Instant
import kotlin.test.Test

class RenderArchiveTest {

    private fun tempDir() = File.createTempFile("rendered", "").apply { delete(); deleteOnExit() }

    private val at = Instant.parse("2026-08-12T11:19:03Z")

    @Test
    fun `names the archived file after the instant it was rendered`() {
        expectThat(renderedFileName(at)).isEqualTo("2026-08-12T11-19-03Z.html")
    }

    @Test
    fun `archives the render and copies it as the index`() {
        val renderedDir = tempDir()
        val indexFile = File(tempDir(), "index.html")

        val archived = archiveRender("<html>gigs</html>", renderedDir, indexFile, at)

        expectThat(archived).isEqualTo(File(renderedDir, "2026-08-12T11-19-03Z.html"))
        expectThat(archived.readText()).isEqualTo("<html>gigs</html>")
        expectThat(indexFile.readText()).isEqualTo("<html>gigs</html>")
    }

    @Test
    fun `keeps earlier renders and overwrites only the index`() {
        val renderedDir = tempDir()
        val indexFile = File(tempDir(), "index.html")

        archiveRender("<html>first</html>", renderedDir, indexFile, at)
        archiveRender("<html>second</html>", renderedDir, indexFile, at.plusSeconds(60))

        expectThat(renderedDir.listFiles()!!.map { it.name }.sorted())
            .isEqualTo(listOf("2026-08-12T11-19-03Z.html", "2026-08-12T11-20-03Z.html"))
        expectThat(File(renderedDir, "2026-08-12T11-19-03Z.html").readText()).isEqualTo("<html>first</html>")
        expectThat(indexFile.readText()).isEqualTo("<html>second</html>")
    }

    @Test
    fun `archives into a directory that doesn't exist yet`() {
        val renderedDir = File(tempDir(), "nested/.rendered")

        val archived = archiveRender("<html>gigs</html>", renderedDir, File(tempDir(), "index.html"), at)

        expectThat(archived.exists()).isTrue()
    }
}
