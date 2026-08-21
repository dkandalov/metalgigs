package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import kotlin.test.Test

class ElectricBallroomGigsSourceTest {

    @Test
    fun `extracts gig events from Electric Ballroom whats-on page`() {
        assertScrapesGigs(
            source = ElectricBallroomGigsSource(cachedClient(), year = 2026),
            size = 89,
            first = Gig(
                GigId(electricBallroom.id, "https://electricballroom.co.uk/lion-babe/"),
                GigTitle("Lion Babe – RESCHEDULED!"),
                GigDate(2026, 8, 13),
                PosterUrl("https://electricballroom.co.uk/wp-content/uploads/2026/07/LION-BABE-.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(electricBallroom.id, "https://electricballroom.co.uk/indiepalooza-tribute-killers-v-monkeys-v-fender-v-oasis-v-kasabian-v-kaiser/"),
                GigTitle("Indiepalooza Tribute – Killers v Monkeys v Fender v Oasis v Kasabian v Kaiser"),
                GigDate(2027, 6, 19),
                PosterUrl("https://electricballroom.co.uk/wp-content/uploads/2026/06/Indiepalooza-2027.jpg"),
                GigDescription(""),
            ),
        )
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
}
