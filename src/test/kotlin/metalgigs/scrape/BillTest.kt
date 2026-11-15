package metalgigs.scrape

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import metalgigs.GigTitle
import metalgigs.Lineup
import org.http4k.ai.llm.LLMError
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.ChatResponse
import org.http4k.ai.llm.model.Content
import org.http4k.ai.llm.model.Message
import org.http4k.ai.model.ModelName
import org.http4k.ai.model.ResponseId
import org.http4k.core.Response
import org.http4k.core.Status.Companion.SERVICE_UNAVAILABLE
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith

// Why the acts are held apart from the title: docs/adr/0015-a-lineup-is-the-acts-a-source-names-held-apart-from-the-title.md
class BillTest {

    @Test
    fun `reads the acts a line bills, cut where only two acts could be meant`() {
        expectThat(lineupBilledBy("Supports : Avralize + Bite Down", noJoinedActs)).isEqualTo(Lineup(listOf("Avralize", "Bite Down")))
        expectThat(lineupBilledBy("PLUS SPECIAL GUESTS -  MARGARITA WITCH CULT / DEAD AIR", noJoinedActs))
            .isEqualTo(Lineup(listOf("MARGARITA WITCH CULT", "DEAD AIR")))
        expectThat(lineupBilledBy("with special guest Dylan Gossett", noJoinedActs)).isEqualTo(Lineup(listOf("Dylan Gossett")))
    }

    @Test
    fun `reads no acts off a strapline, or off a bill that names nobody yet`() {
        expectThat(lineupBilledBy("Chicken Rhythms 35 Year Anniversary Tour", noJoinedActs)).isEqualTo(Lineup.none)
        expectThat(lineupBilledBy("Plus special guests", noJoinedActs)).isEqualTo(Lineup.none)
    }

    // OVO Hydro's Trivium page on 2026-11-15, the one bill read that closes a list of commas this way
    @Test
    fun `drops the ampersand a list of commas ends on`() {
        expectThat(lineupBilledBy("Special Guest: In Flames, Paleface Swiss, & Vola", noJoinedActs))
            .isEqualTo(Lineup(listOf("In Flames", "Paleface Swiss", "Vola")))
    }

    @Test
    fun `asks about a name joined by an ampersand or an and, and about nothing else`() {
        val asked = mutableListOf<String>()
        val joinedActs = JoinedActs { joined -> asked += joined; joined.split(joiner) }

        expectThat(lineupBilledBy("PLUS SPECIAL GUESTS   JESUS & THE ZEALOTS  / DEAD WRITERS", JoinedActs.keptWhole))
            .isEqualTo(Lineup(listOf("JESUS & THE ZEALOTS", "DEAD WRITERS")))
        expectThat(lineupBilledBy("Plus special guests Saint Etienne and Boy Harsher / Sandra", joinedActs))
            .isEqualTo(Lineup(listOf("Saint Etienne", "Boy Harsher", "Sandra")))
        expectThat(asked.toList()).isEqualTo(listOf("Saint Etienne and Boy Harsher"))
    }

    @Test
    fun `cuts a joined name where both readings of the model say it is two acts`() {
        val model = JoinedActsReadByModel(chatAnswering(acts("Lamb Of God", "Bleed From Within"), counted("two_or_more")))

        expectThat(model.apart("Lamb Of God and Bleed From Within")).isEqualTo(listOf("Lamb Of God", "Bleed From Within"))
    }

    // Ruby Doomsday and The Mink, which the first reading cut on 2026-11-15 and the second did not
    @Test
    fun `keeps a joined name whole where the two readings disagree`() {
        expectThat(JoinedActsReadByModel(chatAnswering(acts("Ruby Doomsday", "The Mink"), counted("unsure"))).apart("Ruby Doomsday and The Mink"))
            .isEqualTo(listOf("Ruby Doomsday and The Mink"))
        expectThat(JoinedActsReadByModel(chatAnswering(acts("Fabio & Grooverider"), counted("two_or_more"))).apart("Fabio & Grooverider"))
            .isEqualTo(listOf("Fabio & Grooverider"))
    }

    @Test
    fun `does not ask for a second reading where the first keeps the name whole`() {
        val replies = mutableListOf(acts("Beaux Gris Gris & The Magic Three"))
        val model = JoinedActsReadByModel(chatAnswering(replies))

        expectThat(model.apart("Beaux Gris Gris & The Magic Three")).isEqualTo(listOf("Beaux Gris Gris & The Magic Three"))
        expectThat(replies.toList()).isEqualTo(emptyList())
    }

    @Test
    fun `asks about a bill once, however many nights carry it`() {
        val replies = mutableListOf(acts("Taking Back Sunday", "Beach Bunny"), counted("two_or_more"))
        val model = JoinedActsReadByModel(chatAnswering(replies))

        val nights = List(2) { model.apart("Taking Back Sunday & Beach Bunny") }

        expectThat(nights).isEqualTo(List(2) { listOf("Taking Back Sunday", "Beach Bunny") })
    }

    @Test
    fun `takes each act as the bill writes it, leaving out a note in brackets`() {
        expectThat(actsAsWritten("Grace Ives (5 - 12 April) and Die Spitz (14 April - 10 May)", listOf("grace ives", "Die Spitz")))
            .isEqualTo(listOf("Grace Ives", "Die Spitz"))
        expectThat(actsAsWritten("Coheed and Cambria & Those Damn Crows", listOf("Coheed and Cambria", "Those Damn Crows")))
            .isEqualTo(listOf("Coheed and Cambria", "Those Damn Crows"))
    }

    @Test
    fun `takes nothing from a reading that rewords the bill, adds to it or leaves some of it out`() {
        expectThat(actsAsWritten("Peter and the Test Tube Babies", listOf("Peter", "The Test Tube Babys"))).isEqualTo(null)
        expectThat(actsAsWritten("Victor and the Bully", listOf("special guests", "Victor and the Bully"))).isEqualTo(null)
        expectThat(actsAsWritten("ABC & Haircut 100", listOf("ABC"))).isEqualTo(null)
    }

    @Test
    fun `fails rather than keeping a name whole where the model cannot be asked`() {
        val unavailable = Chat { Failure(LLMError.Http(Response(SERVICE_UNAVAILABLE))) }

        assertFailsWith<IllegalStateException> { JoinedActsReadByModel(unavailable).apart("ABC & Haircut 100") }
    }

    private val noJoinedActs = JoinedActs { joined -> error("asked about \"$joined\", which holds no joined name") }

    private fun chatAnswering(vararg replies: String) = chatAnswering(replies.toMutableList())

    // the two shapes the model's replies are held to
    private fun acts(vararg named: String) = """{"acts": [${named.joinToString { "\"$it\"" }}]}"""

    private fun counted(answer: String) = """{"answer": "$answer"}"""

    private fun chatAnswering(replies: MutableList<String>) = Chat { _ ->
        Success(
            ChatResponse(
                Message.Assistant(listOf(Content.Text(replies.removeFirst()))),
                ChatResponse.Metadata(ResponseId.of("fake-response-id"), ModelName.of("fake-model")),
            ),
        )
    }

    @Test
    fun `reads a bill that says supports, with or without who it is from`() {
        expectThat(lineupBilledBy("plus supports OV SULFUR, EXTERMINATION DISMEMBERMENT, SUNEATER", noJoinedActs))
            .isEqualTo(Lineup(listOf("OV SULFUR", "EXTERMINATION DISMEMBERMENT", "SUNEATER")))
        expectThat(lineupBilledBy("W/Support from: Spencer Mackenzie", noJoinedActs)).isEqualTo(Lineup(listOf("Spencer Mackenzie")))
        expectThat(lineupBilledBy("with special guests REDSPHERE | INTREPID | ORECUS", noJoinedActs))
            .isEqualTo(Lineup(listOf("REDSPHERE", "INTREPID", "ORECUS")))
    }

    // 17 of The Dome's 55 gigs on 2026-11-15 said supports were coming and named none
    @Test
    fun `reads no acts off a bill that says only that there will be supports`() {
        expectThat(lineupBilledBy("plus supports", noJoinedActs)).isEqualTo(Lineup.none)
        expectThat(lineupBilledBy("plus supports TBC", noJoinedActs)).isEqualTo(Lineup.none)
        expectThat(lineupBilledBy("with support", noJoinedActs)).isEqualTo(Lineup.none)
    }

    @Test
    fun `reads a heading that bills its acts as the lineup`() {
        expectThat(lineupHeadedBy(GigTitle("HVALROSS + KARDINAL X + KARAHKA"), noJoinedActs)).isEqualTo(Lineup(listOf("HVALROSS", "KARDINAL X", "KARAHKA")))
        expectThat(lineupHeadedBy(GigTitle("Mindpilot / Flail"), noJoinedActs)).isEqualTo(Lineup(listOf("Mindpilot", "Flail")))
        expectThat(lineupHeadedBy(GigTitle("Nambucca Presents: Tokyo Taboo + Electric Enemy"), noJoinedActs)).isEqualTo(Lineup(listOf("Tokyo Taboo", "Electric Enemy")))
        expectThat(lineupHeadedBy(GigTitle("VIKING SKULL + STORMBRINGER - SOLD OUT"), noJoinedActs)).isEqualTo(Lineup(listOf("VIKING SKULL", "STORMBRINGER")))
    }

    @Test
    fun `leaves a note in brackets, a guest's label and an emoji out of an act's name`() {
        expectThat(lineupHeadedBy(GigTitle("THE HERETIC ORDER (UK) + DAVID HRADILEK BAND (CZ)"), noJoinedActs)).isEqualTo(Lineup(listOf("THE HERETIC ORDER", "DAVID HRADILEK BAND")))
        expectThat(lineupHeadedBy(GigTitle("IMPERIAL TRIUMPHANT + very special guests KNOLL"), noJoinedActs)).isEqualTo(Lineup(listOf("IMPERIAL TRIUMPHANT", "KNOLL")))
        expectThat(lineupHeadedBy(GigTitle("\uD83D\uDD25 RHABSTALLION + BLACK STARS \uD83D\uDD25"), noJoinedActs)).isEqualTo(Lineup(listOf("RHABSTALLION", "BLACK STARS")))
    }

    @Test
    fun `names only the acts a heading names, where it says more are coming`() {
        expectThat(lineupHeadedBy(GigTitle("Luke Hart + special guests"), noJoinedActs)).isEqualTo(Lineup(listOf("Luke Hart")))
        expectThat(lineupHeadedBy(GigTitle("Joey Wit (US) + TBA"), noJoinedActs)).isEqualTo(Lineup(listOf("Joey Wit")))
    }

    // A heading with one name in it may be a band or a night, and nothing says which.
    @Test
    fun `reads no lineup off a heading that bills nothing`() {
        expectThat(lineupHeadedBy(GigTitle("Saxon"), noJoinedActs)).isEqualTo(Lineup.none)
        expectThat(lineupHeadedBy(GigTitle("Union Chapel Guided Tour | January 2027"), noJoinedActs)).isEqualTo(Lineup.none)
    }

    // each of these was listed on 2026-11-15, and each has a piece that is more than an act's name
    @Test
    fun `reads no lineup off a heading where a piece of it is more than a name`() {
        listOf(
            "CASSIDY PARIS - Finish What We Started Tour + JULIET's NOT DEAD",
            "Club Orbit: Ro Nova + Vanity Fairy",
            "CHROM UK TOUR 2027 + special guest PROMENADE CINEMA",
            "Free Entry Showcase SILVER STUN + SLOW DOWN WORLD",
            "Some Weird Sin presents French Toast + Bruno & the OMP",
            "Punk N Roll RendezVous #88 – FREE ENTRY – Healthy Junkies + PollyPikPocketz",
        ).forEach { expectThat(lineupHeadedBy(GigTitle(it), JoinedActs.keptWhole)).isEqualTo(Lineup.none) }
    }

    @Test
    fun `cuts a heading on commas only for a venue that lists its acts that way`() {
        val heading = GigTitle("Rosendo, Doubt Point, House Arrest")

        expectThat(lineupHeadedBy(heading, noJoinedActs, commaLists = true)).isEqualTo(Lineup(listOf("Rosendo", "Doubt Point", "House Arrest")))
        expectThat(lineupHeadedBy(heading, noJoinedActs)).isEqualTo(Lineup.none)
        expectThat(lineupHeadedBy(GigTitle("Jetstream Pony, The Leaf Library, Karen - Club Precious 11"), noJoinedActs, commaLists = true)).isEqualTo(Lineup.none)
    }

    @Test
    fun `asks about a joined name in a heading as it does in a bill`() {
        val joinedActs = JoinedActs { joined -> if (joined == "Babe Haven & Latter") listOf("Babe Haven", "Latter") else listOf(joined) }

        expectThat(lineupHeadedBy(GigTitle("Babe Haven & Latter + Lois and The Loves"), joinedActs))
            .isEqualTo(Lineup(listOf("Babe Haven", "Latter", "Lois and The Loves")))
    }

    @Test
    fun `reads a bill a promoter wrote as a line of its own in the copy`() {
        val copy = listOf("Charlotte Wessels – The Obsession", "Plus Special guest Melissa Bonny", "After 16 years as the front woman for Delain, Charlotte Wessels started releasing music under her own name.")

        expectThat(lineupBilledInCopy(copy, noJoinedActs)).isEqualTo(Lineup(listOf("Melissa Bonny")))
        expectThat(lineupBilledInCopy(listOf("W/Support from: Spencer Mackenzie"), noJoinedActs)).isEqualTo(Lineup(listOf("Spencer Mackenzie")))
    }

    // Islington Assembly Hall's Fleetwood Mac night, whose copy opens a sentence on "plus"
    @Test
    fun `reads no bill off copy that only says plus`() {
        val copy = listOf("They will be performing the album in its entirety,", "plus loads of other well-known F Mac classics.")

        expectThat(lineupBilledInCopy(copy, noJoinedActs)).isEqualTo(Lineup.none)
    }

    // bills off The Dome and Union Chapel on 2026-11-15, none of them put to the model
    @Test
    fun `cuts a bill of shouted names on an and written in lower case`() {
        expectThat(lineupBilledBy("plus supports from COSMIC JOKE, SUPERNOVA and BAD BEAT", noJoinedActs))
            .isEqualTo(Lineup(listOf("COSMIC JOKE", "SUPERNOVA", "BAD BEAT")))
        expectThat(lineupBilledBy("plus supports HUNDRED YEAR OLD MAN and CAIRNS", noJoinedActs))
            .isEqualTo(Lineup(listOf("HUNDRED YEAR OLD MAN", "CAIRNS")))
        expectThat(lineupHeadedBy(GigTitle("KATATONIA and MY DYING BRIDE + GGGOLDDD"), noJoinedActs))
            .isEqualTo(Lineup(listOf("KATATONIA", "MY DYING BRIDE", "GGGOLDDD")))
    }

    @Test
    fun `takes a shouted and beside a lower case one as a word in an act's name`() {
        expectThat(lineupBilledBy("with special guests ROME and OF THE WAND AND THE MOON", noJoinedActs))
            .isEqualTo(Lineup(listOf("ROME", "OF THE WAND AND THE MOON")))
    }

    // the case says nothing where the names are not shouted, or where the joiner is an ampersand
    @Test
    fun `still asks about a joined name whose case says nothing`() {
        val asked = mutableListOf<String>()
        val joinedActs = JoinedActs { joined -> asked += joined; listOf(joined) }

        lineupBilledBy("with special guests The Grey and Moon Reaper", joinedActs)
        lineupBilledBy("plus supports KATE GORE AND THE SCREAMERS", joinedActs)
        lineupBilledBy("plus supports Feeder and A", joinedActs)
        lineupBilledBy("plus supports SUPERNOVA and JESUS & THE ZEALOTS", joinedActs)

        expectThat(asked.toList()).isEqualTo(listOf("The Grey and Moon Reaper", "KATE GORE AND THE SCREAMERS", "Feeder and A", "JESUS & THE ZEALOTS"))
    }
}
