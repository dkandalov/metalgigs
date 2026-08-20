package metalgigs.render

import java.io.File
import java.time.LocalDate

private const val siteUrl = "https://metalgigs.london/"

fun sitemapXml(lastModified: LocalDate): String =
    """
        <?xml version="1.0" encoding="UTF-8"?>
        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
            <url>
                <loc>$siteUrl</loc>
                <lastmod>$lastModified</lastmod>
            </url>
        </urlset>
    """.trimIndent() + "\n"

// The scheduled job renders three times a day and most of those runs produce the html that is
// already published, so a lastmod taken from the render instant would announce a change on every
// one of them. A crawler that finds the date unreliable stops believing it, hence dating the page
// by the render that actually changed it - and a date rather than an instant, since the archive in
// .rendered/ is where sub-day precision lives.
fun updateSitemap(sitemapFile: File, publishedHtml: String?, html: String, on: LocalDate): Boolean {
    if (sitemapFile.exists() && publishedHtml == html) return false
    sitemapFile.writeText(sitemapXml(on))
    return true
}
