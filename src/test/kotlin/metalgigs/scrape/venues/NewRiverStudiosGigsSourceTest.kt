package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.containsExactly
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.contains
import strikt.assertions.message
import kotlin.test.Test

// Why this reading surface and paging: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
// Why a heading is a lineup: docs/adr/0015-a-lineup-is-the-acts-a-source-names-held-apart-from-the-title.md
class NewRiverStudiosGigsSourceTest {

    // The whole calendar in one page of the api, recorded 2026-11-26. It opens on a night already
    // gone: asked that evening, the api's own window began at 03:00 the day before.
    @Test
    fun `extracts gig events from New River Studios' events api`() {
        val events = assertScrapesGigs(
            source = NewRiverStudiosGigsSource(cachedClient(), JoinedActs.keptWhole),
            size = 11,
            first = Gig(
                GigId(newRiverStudios.id, GigUrl("https://newriverstudios.com/event/lbu-9-demonstration-of-power-exit-life-life-of-one-violent-offence/")),
                GigTitle("LBU #9: Demonstration of Power, Exit Life, Life of One, Violent Offence"),
                GigDate(2026, 11, 25),
                PosterUrl("https://newriverstudios.com/wp-content/uploads/2026/11/lbu9.jpeg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
            last = Gig(
                GigId(newRiverStudios.id, GigUrl("https://newriverstudios.com/event/baba-yagas-hut-presents-france-2/")),
                GigTitle("Baba Yaga’s Hut presents: FRANCE"),
                GigDate(2027, 2, 13),
                PosterUrl("https://newriverstudios.com/wp-content/uploads/2026/10/France-12-Feb-2027-WEB.jpg"),
                GigDescription(""),
                Lineup.none,
                status = null,
            ),
        )

        expectThat(events.map { it.id.url }.distinct()).hasSize(11)
        expectThat(events.first { it.title == GigTitle("Stygian Invocation Warm-Up") }.description.value)
            .isEqualTo("Stygian Invocation Warm-Up Condenados Skumhammer Deikapitation New River Studios London Tickets: linktr.ee/stygianprods")
        expectThat(events.first { it.title == GigTitle("Rat Cage, Traidora, Sarsour, Gutted, M474K") }.lineup)
            .isEqualTo(Lineup(listOf("Rat Cage", "Traidora", "Sarsour", "Gutted", "M474K")))
        // a heading that opens on whoever put the night on bills what follows
        expectThat(events.first { it.title == GigTitle("GET IN HER EARS presents: Thwack! (single launch), Candar, Joe Plastic") }.lineup)
            .isEqualTo(Lineup(listOf("Thwack!", "Candar", "Joe Plastic")))
        expectThat(events.first { it.title == GigTitle("Baba Yaga’s Hut presents: FRANCE") }.lineup).isEqualTo(Lineup.none)
    }

    @Test
    fun `reads every page the api names, a title's entities and a description's markup undone`() {
        val asked = mutableListOf<String>()
        val client = apiClient(
            total = 2,
            pages = listOf(
                listOf(event("one", "Baba Yaga&#8217;s Hut presents: FRANCE", "2027-02-12 19:30:00", description = "<p>Hurdy-gurdy <strong>drone</strong>.</p>\n<p>Doors 7.30pm</p>")),
                listOf(event("two", "Corpusmilner &#038; Posse", "2027-02-13 19:30:00")),
            ),
            asked,
        )

        val events = NewRiverStudiosGigsSource(client, JoinedActs.keptWhole).latestGigs()

        expectThat(events.map { it.title }).containsExactly(GigTitle("Baba Yaga’s Hut presents: FRANCE"), GigTitle("Corpusmilner & Posse"))
        expectThat(events.map { it.date }).containsExactly(GigDate(2027, 2, 12), GigDate(2027, 2, 13))
        expectThat(events.first().description).isEqualTo(GigDescription("Hurdy-gurdy drone. Doors 7.30pm"))
        expectThat(asked).containsExactly(
            "https://newriverstudios.com/wp-json/tribe/events/v1/events?per_page=50&page=1",
            "https://newriverstudios.com/wp-json/tribe/events/v1/events?per_page=50&page=2",
        )
    }

    @Test
    fun `fails where the events read fall short of the total the api names`() {
        val client = apiClient(total = 3, pages = listOf(listOf(event("one", "One", "2027-02-12 19:30:00"))))

        expectThrows<IllegalStateException> { NewRiverStudiosGigsSource(client, JoinedActs.keptWhole).latestGigs() }
            .message.isNotNull().contains("Read 1 of the 3 events")
    }

    @Test
    fun `fails naming the gig whose event has no image`() {
        val client = apiClient(total = 1, pages = listOf(listOf(event("one", "One", "2027-02-12 19:30:00", image = "false"))))

        expectThrows<IllegalStateException> { NewRiverStudiosGigsSource(client, JoinedActs.keptWhole).latestGigs() }
            .message.isNotNull().contains("No poster for https://newriverstudios.com/event/one/")
    }

    private fun apiClient(total: Int, pages: List<List<String>>, asked: MutableList<String> = mutableListOf()): HttpHandler = { request ->
        asked += request.uri.toString()
        val page = request.query("page")!!.toInt()
        Response(OK).body("""{"events":[${pages[page - 1].joinToString(",")}],"total":$total,"total_pages":${pages.size}}""")
    }

    // `venue` is the empty list the api writes on an event with none set.
    private fun event(
        slug: String,
        title: String,
        startDate: String,
        description: String = "<p>Doors 7.30pm</p>",
        image: String = """{"url":"https://newriverstudios.com/wp-content/uploads/2026/11/$slug.jpg","width":1080,"height":1350}""",
    ) = """{"url":"https://newriverstudios.com/event/$slug/","title":${quoted(title)},"start_date":"$startDate","description":${quoted(description)},"image":$image,"venue":[]}"""

    private fun quoted(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
