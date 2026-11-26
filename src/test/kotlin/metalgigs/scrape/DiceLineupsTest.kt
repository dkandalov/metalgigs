package metalgigs.scrape

import metalgigs.*
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.SERVICE_UNAVAILABLE
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import kotlin.test.Test

// Why a venue's source also asks Dice: docs/adr/0015-a-lineup-is-the-acts-a-source-names-held-apart-from-the-title.md
class DiceLineupsTest {

    @Test
    fun `finds the event that is a gig by its night and its name`() {
        val saxon = event("Saxon", "2026-11-22", "Saxon", "Armored Saint")
        val events = listOf(event("Drake Milligan", "2026-11-20", "Drake Milligan"), saxon, event("Saxon", "2026-11-23", "Saxon"))

        expectThat(eventFor(gig("Saxon", "2026-11-22"), events)).isEqualTo(saxon)
    }

    // Union Chapel heads a gig "Jake Bugg 25 FEB", and KOKO's a night its act plays - "KOKO
    // Electronic: Marco Carola (Night 2)", which Dice lists under another name with the act on it
    @Test
    fun `finds an event whose name the heading holds, or which bills an act the heading names`() {
        val jakeBugg = event("Jake Bugg", "2027-02-25", "Jake Bugg")
        val marcoCarola = event("Music On: Night Two", "2027-03-06", "Marco Carola", "Jamback")

        expectThat(eventFor(gig("Jake Bugg 25 FEB", "2027-02-25"), listOf(jakeBugg))).isEqualTo(jakeBugg)
        expectThat(eventFor(gig("KOKO Electronic: Marco Carola (Night 2)", "2027-03-06"), listOf(marcoCarola))).isEqualTo(marcoCarola)
    }

    @Test
    fun `finds no event for a gig on a night it shares with one it isn't`() {
        val events = listOf(event("URNE", "2026-11-19", "URNE", "The Grey", "Moon Reaper"))

        expectThat(eventFor(gig("MASON HILL", "2026-11-19"), events)).isEqualTo(null)
        expectThat(eventFor(gig("URNE", "2026-11-20"), events)).isEqualTo(null)
    }

    @Test
    fun `matches a short name only where it is the whole of the heading`() {
        val bis = event("Bis", "2026-12-05", "Bis", "Liines")
        val a = event("Rock Night", "2026-12-05", "A")

        expectThat(eventFor(gig("Bis", "2026-12-05"), listOf(bis))).isEqualTo(bis)
        expectThat(eventFor(gig("Barbican Estate", "2026-12-05"), listOf(bis, a))).isEqualTo(null)
    }

    @Test
    fun `takes the fuller bill where an early and a late show share a night and a name`() {
        val early = event("Skye Newman", "2026-11-21", "Skye Newman")
        val late = event("Skye Newman", "2026-11-21", "Skye Newman", "Amma")

        expectThat(eventFor(gig("Skye Newman", "2026-11-21"), listOf(early, late))).isEqualTo(late)
    }

    // Union Chapel's listing on Dice on 2026-11-15
    @Test
    fun `leaves out an entry that is only two of the others joined`() {
        expectThat(actsOn(event("Ben Watt and Tracey Thorn", "2026-11-30", "Ben Watt", "Tracey Thorn", "Ben Watt and Tracey Thorn")))
            .isEqualTo(listOf("Ben Watt", "Tracey Thorn"))
        expectThat(actsOn(event("Seun Kuti", "2026-11-30", "Seun Kuti & Egypt 80", "Seun Kuti"))).isEqualTo(listOf("Seun Kuti & Egypt 80", "Seun Kuti"))
    }

    @Test
    fun `gives a gig the artists Dice lists for it, asking once for a venue's whole listing`() {
        val asked = mutableListOf<String>()
        val lineups = DiceLineups("Eventim Apollo") { venue ->
            asked += venue
            listOf(event("Saxon", "2026-11-22", "Saxon", "Armored Saint "), event("The Fray", "2026-11-16", "The Fray"))
        }

        expectThat(lineups.lineupFor(gig("Saxon", "2026-11-22"))).isEqualTo(Lineup(listOf("Saxon", "Armored Saint")))
        expectThat(lineups.lineupFor(gig("The Fray", "2026-11-16"))).isEqualTo(Lineup(listOf("The Fray")))
        expectThat(lineups.lineupFor(gig("December 10", "2026-12-10"))).isEqualTo(Lineup.none)
        expectThat(asked.toList()).isEqualTo(listOf("Eventim Apollo"))
    }

    @Test
    fun `names no lineup, rather than failing the venue, where Dice can't be read`() {
        val lineups = DiceLineups("Scala") { error("Failed to fetch https://partners-endpoint.dice.fm/api/v2/events: 503") }

        expectThat(lineups.lineupFor(gig("DE STAAT", "2026-11-15"))).isEqualTo(Lineup.none)
        expectThat(lineups.lineupFor(gig("Widowspeak", "2026-11-16"))).isEqualTo(Lineup.none)
    }

    @Test
    fun `keeps Dice's lineup where it is a bill, or where the venue's own source named nobody`() {
        val supports = Lineup(listOf("The Grey and Moon Reaper"))
        val bill = Lineup(listOf("URNE", "The Grey", "Moon Reaper"))
        val headliner = Lineup(listOf("URNE"))

        expectThat(preferred(own = supports, onDice = bill)).isEqualTo(bill)
        expectThat(preferred(own = Lineup.none, onDice = headliner)).isEqualTo(headliner)
        expectThat(preferred(own = supports, onDice = headliner)).isEqualTo(supports)
        expectThat(preferred(own = supports, onDice = Lineup.none)).isEqualTo(supports)
    }

    @Test
    fun `gives each of a venue's gigs the lineup preferred for it and changes nothing else`() {
        val urne = gig("URNE", "2026-11-19").copy(lineup = Lineup(listOf("The Grey and Moon Reaper")))
        val gong = gig("GONG", "2026-11-28")
        val onDice = DiceLineups("The Dome") { listOf(event("URNE", "2026-11-19", "URNE", "The Grey", "Moon Reaper")) }

        expectThat(listOf(urne, gong).withLineups(onDice))
            .isEqualTo(listOf(urne.copy(lineup = Lineup(listOf("URNE", "The Grey", "Moon Reaper"))), gong))
    }

    @Test
    fun `asks Dice for the venue by the name Dice gives it, through the source's own client`() {
        val asked = mutableListOf<Request>()
        val dice: HttpHandler = { request ->
            asked += request
            Response(OK).body(
                """{"data":[{"name":"DE STAAT","perm_name":"de-staat","hash":"abc123","date":"2026-11-15T19:00:00Z","timezone":"Europe/London","images":[],""" +
                    """"raw_description":"","status":"on-sale","artists":["De Staat","Personal Trainer"],"type_tags":["music:gig"]}],"links":{}}""",
            )
        }

        expectThat(listOf(gig("DE STAAT", "2026-11-15")).withLineupsOnDiceAt("Union Chapel", dice).single().lineup)
            .isEqualTo(Lineup(listOf("De Staat", "Personal Trainer")))
        expectThat(asked.single().query("filter[venues][]")).isEqualTo("Union Chapel")
    }

    @Test
    fun `leaves a venue's gigs as its own source read them where its client can't reach Dice`() {
        val supports = Lineup(listOf("Personal Trainer"))
        val gigs = listOf(gig("DE STAAT", "2026-11-15").copy(lineup = supports), gig("Widowspeak", "2026-11-16"))

        expectThat(gigs.withLineupsOnDiceAt("Scala") { Response(SERVICE_UNAVAILABLE) }).isEqualTo(gigs)
    }

    @Test
    fun `gives a shared source's gigs the lineups Dice lists at the venue`() {
        val gong = gig("GONG", "2026-11-28")
        val source = object : GigsSource {
            override val venue = Venue(VenueId("dome"), "The Dome")
            override fun latestGigs() = listOf(gong)
        }
        val dice: HttpHandler = {
            Response(OK).body(
                """{"data":[{"name":"Gong","perm_name":"gong","hash":"abc123","date":"2026-11-28T19:00:00Z","timezone":"Europe/London","images":[],""" +
                    """"raw_description":"","status":"on-sale","artists":["Gong","Ozric Tentacles"],"type_tags":["music:gig"]}],"links":{}}""",
            )
        }

        expectThat(WithLineupsOnDiceAt("The Dome", source, dice).latestGigs())
            .isEqualTo(listOf(gong.copy(lineup = Lineup(listOf("Gong", "Ozric Tentacles")))))
    }

    private fun gig(title: String, date: String) = Gig(
        GigId(VenueId("a-venue"), GigUrl("https://example.com/${title.lowercase().replace(' ', '-')}")),
        GigTitle(title),
        GigDate.parse(date),
        PosterUrl("https://example.com/poster.jpg"),
        GigDescription(""),
        Lineup.none,
        status = null,
    )

    // doors at seven in the evening, so the night Dice stamps in UTC is the night it is in London
    private fun event(name: String, date: String, vararg artists: String) = DicePartnerEvent(
        name = name,
        permName = name.lowercase(),
        hash = "abc123",
        date = "${date}T19:00:00Z",
        timezone = "Europe/London",
        images = emptyList(),
        rawDescription = "",
        status = "on-sale",
        artists = artists.toList(),
        typeTags = listOf("music:gig"),
    )
}
