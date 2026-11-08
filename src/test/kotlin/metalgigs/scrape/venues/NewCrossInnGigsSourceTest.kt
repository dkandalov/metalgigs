package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test

// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
// Why this reading surface and paging: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
// Why a title is composed from the bill: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
class NewCrossInnGigsSourceTest {

    // the page opens on the current month, so all but the first two months here come from the
    // dropdown's own admin-ajax call rather than the page itself
    @Test
    fun `extracts gig events from New Cross Inn gigs page, following the months dropdown`() {
        val events = assertScrapesGigs(
            source = NewCrossInnGigsSource(cachedClient()),
            size = 118,
            first = Gig(
                GigId(newCrossInn.id, GigUrl("https://pit.live/events/greenhat")),
                GigTitle("GREENHAT"),
                GigDate(2026, 8, 8),
                PosterUrl("https://pit.live/uploads/user/2026/07/07/640x480/5d05ygXA94bMG95I.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(newCrossInn.id, GigUrl("https://pit.live/events/level-up-festival-7")),
                GigTitle("Level Up Festival 7"),
                GigDate(2027, 7, 23),
                PosterUrl("https://pit.live/uploads/user/2026/07/24/640x480/t8YfuAmMlTMW6ilv.jpg"),
                GigDescription(""),
            ),
        )

        // a gig five months past what the page itself lists, and the one that showed the dropdown
        // was being missed - the page opens on August, and this is only in the February fragment
        expectThat(events.map { it.id.url }).contains(GigUrl("https://pit.live/events/ghost-uk-1"))
        // the month the page opens on is in the dropdown too, so its gigs arrive from both
        expectThat(events.map { it.id.url }.distinct().size).isEqualTo(events.size)
    }

    @Test
    // the attribute holds a JavaScript string literal rather than markup - angle brackets,
    // quotes and ampersands all arrive as unicode escapes - so a description read straight off it
    // is escapes and tags instead of the gig's own copy. The attribute here is verbatim from a
    // real listing, escapes and all, because a hand-written one without them tests nothing.
    fun `decodes New Cross Inn's client-rendered description into plain text`() {
        val html = """
            <p x-ref="desc" x-html="'\u003Ca href=\u0022https:\/\/www.facebook.com\/newcrosslive\u0022\u003E\u003Cstrong\u003ENew Cross Live\u003C\/strong\u003E\u003C\/a\u003E\u0026nbsp;presents\u003Cbr \/\u003E\r\n\u003Cbr \/\u003E\r\n\u003Cstrong\u003E\u003Ca href=\u0022https:\/\/www.facebook.com\/GhostUKTributeBand\u0022\u003EGhost UK\u003C\/a\u003E\u003C\/strong\u003E\u003Cbr \/\u003E\r\nThe Authentic UK Tribute to the band Ghost!\u003Cbr \/\u003E\r\n\u003Ca href=\u0022https:\/\/www.facebook.com\/GhostUKTributeBand\u0022\u003Ehttps:\/\/www.facebook.com\/GhostUKTributeBand\u003C\/a\u003E\u003Cbr \/\u003E\r\n\u003Cbr \/\u003E\r\nFriday 13th February 2027\u003Cbr \/\u003E\r\nNew Cross Inn\u003Cbr \/\u003E\r\nDoors 6pm\u003Cbr \/\u003E\r\nTickets \u0026pound;15 ADV STBF'"></p>
            <div>KINGS OF THRASH</div>
        """.trimIndent()

        val pageText = NewCrossInnGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("The Authentic UK Tribute to the band Ghost!")).isTrue()
        expectThat(pageText.contains("New Cross Live")).isTrue()
        // the entity decoded too, so a price reads as one rather than as an entity name
        expectThat(pageText.contains("Tickets £15 ADV STBF")).isTrue()
        expectThat(pageText.contains("u003C")).isEqualTo(false)
        expectThat(pageText.contains("<br")).isEqualTo(false)
        expectThat(pageText.contains("href")).isEqualTo(false)
        expectThat(pageText.contains("KINGS OF THRASH")).isEqualTo(false)
    }

    @Test
    fun `titles a gig with the acts its event page names as performers`() {
        expectThat(titleOf("Lagrimas", "Lagrimas", "Hemiptera", "Sevy Verna", "Clouded"))
            .isEqualTo(GigTitle("Lagrimas / Hemiptera / Sevy Verna / Clouded"))
    }

    // Brakelight's page lists a support ahead of the act the gig is listed as
    @Test
    fun `keeps the gig's own name first whatever order the performers come in`() {
        expectThat(titleOf("Brakelight", "Sore Heads Club", "Brakelight", "Fear of Motion"))
            .isEqualTo(GigTitle("Brakelight / Sore Heads Club / Fear of Motion"))
    }

    @Test
    fun `leaves out a performer the gig's name already bills`() {
        expectThat(titleOf("Babe Haven & Latter", "Babe Haven", "latter", "Brick", "Inner Dog"))
            .isEqualTo(GigTitle("Babe Haven & Latter / Brick / Inner Dog"))
        expectThat(titleOf("Steal This Band + Tonedefs", "Steal This Band", "Tonedefs"))
            .isEqualTo(GigTitle("Steal This Band + Tonedefs"))
    }

    // Capital Death Fest and Trans Night Of Rage name no act the gig is listed as, and Reality
    // Unfolds 2027 names 23 - a programme rather than a bill
    @Test
    fun `keeps the venue's title where no performer is what the gig is listed as`() {
        expectThat(titleOf("Capital Death Fest", "Infected Dissaray", "Rectal Smegma", "Pighead"))
            .isEqualTo(GigTitle("Capital Death Fest"))
    }

    // Ghost UK's page gives its one performer as an object rather than an array of one
    @Test
    fun `reads a lone performer given as an object rather than an array`() {
        val page = pageOf("""<script type="application/ld+json">{"@type":"Event","name":"Ghost UK","performer":{"@type":"PerformingGroup","name":"Ghost UK"}}</script>""")

        expectThat(NewCrossInnGigsSource(noHttp).performersOn(page)).isEqualTo(listOf("Ghost UK"))
    }

    private fun titleOf(listedAs: String, vararg performers: String): GigTitle {
        val json = performers.joinToString(",", prefix = "[", postfix = "]") { """{"@type":"PerformingGroup","name":"$it"}""" }
        val page = pageOf("""<script type="application/ld+json">{"@type":"Event","name":"$listedAs","performer":$json}</script>""")
        val lineup = NewCrossInnGigsSource(noHttp).performersOn(page)
        return titleWithLineup(GigTitle(listedAs), lineup) ?: GigTitle(listedAs)
    }
}
