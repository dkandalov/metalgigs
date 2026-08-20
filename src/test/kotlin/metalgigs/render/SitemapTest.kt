package metalgigs.render

import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isTrue
import java.io.File
import java.time.LocalDate
import kotlin.test.Test

class SitemapTest {

    private fun tempFile() = File.createTempFile("sitemap", ".xml").apply { delete(); deleteOnExit() }

    private val on = LocalDate.parse("2026-08-12")

    @Test
    fun `dates the site's one url`() {
        expectThat(sitemapXml(on)).isEqualTo(
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                    <url>
                        <loc>https://metalgigs.london/</loc>
                        <lastmod>2026-08-12</lastmod>
                    </url>
                </urlset>
            """.trimIndent() + "\n"
        )
    }

    @Test
    fun `dates the page by the render that changed it`() {
        val sitemapFile = tempFile()

        expectThat(updateSitemap(sitemapFile, "<html>yesterday</html>", "<html>today</html>", on)).isTrue()
        expectThat(sitemapFile.readText()).contains("<lastmod>2026-08-12</lastmod>")
    }

    @Test
    fun `leaves the date alone when the render matches the published page`() {
        val sitemapFile = tempFile()
        updateSitemap(sitemapFile, null, "<html>gigs</html>", on)

        expectThat(updateSitemap(sitemapFile, "<html>gigs</html>", "<html>gigs</html>", on.plusDays(3))).isFalse()
        expectThat(sitemapFile.readText()).contains("<lastmod>2026-08-12</lastmod>")
    }

    @Test
    fun `writes a sitemap that doesn't exist yet even though the page is unchanged`() {
        val sitemapFile = tempFile()

        expectThat(updateSitemap(sitemapFile, "<html>gigs</html>", "<html>gigs</html>", on)).isTrue()
        expectThat(sitemapFile.exists()).isTrue()
    }
}
