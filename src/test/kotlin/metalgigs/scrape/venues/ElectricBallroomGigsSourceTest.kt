package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNull
import strikt.assertions.isTrue
import kotlin.test.Test

// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
class ElectricBallroomGigsSourceTest {

    @Test
    fun `extracts gig events from Electric Ballroom whats-on page`() {
        val gigs = assertScrapesGigs(
            source = ElectricBallroomGigsSource(cachedClient(), year = 2026),
            size = 87,
            first = Gig(
                GigId(electricBallroom.id, GigUrl("https://electricballroom.co.uk/lion-babe/")),
                GigTitle("Lion Babe – RESCHEDULED!"),
                GigDate(2026, 8, 13),
                PosterUrl("https://electricballroom.co.uk/wp-content/uploads/2026/07/LION-BABE-.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(electricBallroom.id, GigUrl("https://electricballroom.co.uk/indiepalooza-tribute-killers-v-monkeys-v-fender-v-oasis-v-kasabian-v-kaiser/")),
                GigTitle("Indiepalooza Tribute – Killers v Monkeys v Fender v Oasis v Kasabian v Kaiser"),
                GigDate(2027, 6, 19),
                PosterUrl("https://electricballroom.co.uk/wp-content/uploads/2026/06/Indiepalooza-2027.jpg"),
                GigDescription(""),
            ),
        )

        // the venue lists Bongo's Bingo among its gigs, two of them in this listing, and the title
        // is checked here rather than the ticket link the source drops them by
        expectThat(gigs.none { it.title.value.contains("bingo", ignoreCase = true) }).isTrue()
    }

    // the policy paragraph and the meta line are verbatim from a real listing, where together with
    // the repeated title they ran longer than the gig's own copy
    @Test
    fun `scopes Electric Ballroom page text to the content column, dropping the age policy`() {
        val html = """
            <header><nav><a>Whats On</a></nav><span class="header-address">184 CAMDEN HIGH STREET, CAMDEN TOWN, LONDON, NW1 8QP</span></header>
            <article>
                <h1>Doom Night</h1>
                <div class="cf"><a>← Back</a>
                    <div class="article-content">
                        <p>Doom metal night!</p>
                        <p>Please note this show is 14+ (under 16s must be accompanied by an 18+ adult). Valid physical photo ID is required for entry!</p>
                    </div>
                    <div class="event-meta">7.00PM | £25</div>
                    <div class="buy-share-event">Buy Tickets</div>
                </div>
            </article>
            <footer><a>Facebook</a></footer>
        """.trimIndent()

        val pageText = ElectricBallroomGigsSource(noHttp, year = 2026).eventPageContent(pageOf(html))!!

        expectThat(pageText).isEqualTo("Doom metal night!")
    }

    // the same policy, worded two other ways the venue also uses
    @Test
    fun `drops the Electric Ballroom age policy however it is worded`() {
        val phrasings = listOf(
            "Strictly 18+ / physical photo ID required at entry.",
            "Please note this show is 14+ (under 16s must be accompanied by an 18+ adult / Proof of age is required at entry.)",
        )

        phrasings.forEach { policy ->
            val html = """<div class="article-content"><p>Doom metal night!</p><p>$policy</p></div>"""

            val pageText = ElectricBallroomGigsSource(noHttp, year = 2026).eventPageContent(pageOf(html))!!

            expectThat(pageText).isEqualTo("Doom metal night!")
        }
    }

    // an .article-content that is there and empty is a page whose promoter wrote nothing, where a
    // redesign would leave no .article-content at all - only the second is a selector failure
    @Test
    fun `tells an empty Electric Ballroom content block from a missing one`() {
        val source = ElectricBallroomGigsSource(noHttp, year = 2026)

        expectThat(source.eventPageContent(pageOf("""<div class="article-content">   </div>"""))).isEqualTo("")
        expectThat(source.eventPageContent(pageOf("<article><h1>Doom Night</h1></article>"))).isNull()
    }
}
