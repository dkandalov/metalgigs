package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test

// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
class UnionChapelGigsSourceTest {

    @Test
    fun `extracts gig events from Union Chapel's what's on page`() {
        val events = assertScrapesGigs(
            source = UnionChapelGigsSource(cachedClient(), JoinedActs.keptWhole),
            size = 119,
            first = Gig(
                GigId(unionChapel.id, GigUrl("https://unionchapel.org.uk/whats-on/mavis-staples-12-aug-2026")),
                GigTitle("MAVIS STAPLES: 12 AUG 2026"),
                GigDate(2026, 8, 12),
                PosterUrl("https://s3.eu-west-2.amazonaws.com/cdn.unionchapel.org.uk/files/MAVIS%20S.png"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(unionChapel.id, GigUrl("https://unionchapel.org.uk/whats-on/fairport-convention-60th-anniversary")),
                GigTitle("Fairport Convention 60th Anniversary"),
                GigDate(2027, 5, 27),
                PosterUrl("https://s3.eu-west-2.amazonaws.com/cdn.unionchapel.org.uk/files/Fairport%20Convention%2060th%20logo.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )

        // the whole listing comes back on one page, with a poster on every card. Document order is
        // *not* chronological - the page sorts client-side, which is why the date is read from
        // data-chron rather than inferred from position as some other venues' listings allow
        expectThat(events.map { it.id.url }.distinct()).hasSize(119)
    }

    // the sections after "Book For A Pre-Show Dinner" are verbatim from a real listing, where they
    // run to some 1,850 characters identical on every page
    @Test
    fun `scopes Union Chapel page text to the gig's own copy and the event-information sidebar`() {
        val html = """
            <nav><a>Whats On</a></nav>
            <div id="content">
                <article class="pt-4">
                    <h1>Doom Night</h1>
                    <h4>For tickets to this event click BOOK NOW button above</h4>
                    <h4>Scroll down for info on reserving a pre-show meal with Margins Cafe.</h4>
                    <p>Doom metal night!</p>
                    <h4>Book For A Pre-Show Dinner</h4>
                    <p>The Margins Cafe serves delicious, freshly prepared food at gigs and events.</p>
                    <p>More Information:</p>
                    <p>Alcohol consumption will be limited to the bar area only.</p>
                </article>
            </div>
            <aside><div class="sidebar p-3"><h6>WHEN</h6><p>7pm</p></div></aside>
            <footer class="pt-4"><a>Instagram</a></footer>
        """.trimIndent()

        val pageText = UnionChapelGigsSource(noHttp, JoinedActs.keptWhole).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("7pm")).isTrue()
        expectThat(pageText.contains("Margins Cafe")).isEqualTo(false)
        expectThat(pageText.contains("Alcohol consumption")).isEqualTo(false)
        expectThat(pageText.contains("BOOK NOW")).isEqualTo(false)
        expectThat(pageText.contains("Whats On")).isEqualTo(false)
        expectThat(pageText.contains("Instagram")).isEqualTo(false)
    }

    // ROME's page, where the promoter typed the bill as two subheadings under the gig's name
    @Test
    fun `takes the acts billed in the subheadings under a gig's name as its lineup`() {
        val page = pageOf("""<article class="pt-4"><h1>ROME</h1><h3>plus very special guests</h3><h3>OF THE WAND &amp; THE MOON</h3><p>&nbsp;</p><h4>To book tickets</h4><p>ROME will be rewilding the sacred.</p></article>""")

        expectThat(UnionChapelGigsSource(noHttp, JoinedActs.keptWhole).billedLineup(page)).isEqualTo(Lineup(listOf("OF THE WAND & THE MOON")))
    }

    @Test
    fun `names no lineup where nothing is billed under a gig's name`() {
        val unnamed = pageOf("""<article class="pt-4"><h1>Jake Bugg</h1><h3>+ special guests</h3><p>Copy.</p></article>""")
        val straightToCopy = pageOf("""<article class="pt-4"><h1>Darren Kiely</h1><p>Copy.</p><h3>plus support from Someone</h3></article>""")

        expectThat(UnionChapelGigsSource(noHttp, JoinedActs.keptWhole).billedLineup(unnamed)).isEqualTo(Lineup.none)
        expectThat(UnionChapelGigsSource(noHttp, JoinedActs.keptWhole).billedLineup(straightToCopy)).isEqualTo(Lineup.none)
    }
}
