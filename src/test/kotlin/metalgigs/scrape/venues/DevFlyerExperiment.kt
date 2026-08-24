package metalgigs.scrape.venues

import metalgigs.Gig
import metalgigs.GigDate
import metalgigs.httpClient
import metalgigs.ollamaCallTimeout
import metalgigs.Ollama
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.model.SystemPrompt
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

// An experiment rather than a check on this project's behaviour: it asks the model the source uses
// to read flyers whose gigs are already known, and reports where it differs. It needs a running
// ollama and takes about a minute a flyer, so the suite skips it unless DEV_FLYER is set - the same
// way RECORD_TRAFFIC gates the one other thing here that reaches outside the JVM. Setting it isn't
// an input Gradle can see, so a second run of an unchanged experiment is up-to-date and prints
// nothing at all rather than running: --rerun-tasks is what asks the model again.
//
// July is the flyer that matters. The extraction prompt was written against August's, so August only
// says the prompt still does what it was fitted to do, where July is a month it has never seen: same
// venue, same designer, a different set of rows, and the one that says whether reading these flyers
// generalises or was learned.
class DevFlyerExperiment {

    @Test
    fun `reads the flyers whose gigs are already known`() {
        assumeTrue(System.getenv("DEV_FLYER") != null, "set DEV_FLYER=1 to run this experiment")

        val misread = flyers.filter { flyer ->
            val gigs = gigsRead(flyer)
            report(flyer, gigs)
            // The dates are the flyer's own facts, so a missing one is a gig that would go unlisted
            // and a spare one is a night the venue isn't running. How the model words a title is
            // transcription, reported above to be read rather than asserted on.
            gigs.map { it.date }.toSet() != flyer.gigs.map { it.first }.toSet()
        }

        if (misread.isNotEmpty()) fail("${misread.joinToString { it.month }} misread - see the report above")
    }

    private fun gigsRead(flyer: Flyer): List<Gig> {
        val chat = Chat.Ollama(httpClient(ollamaCallTimeout), SystemPrompt.of(flyerExtractionSystemPrompt))
        return DevGigSource(servingOnly(flyer), chat).latestGigs()
    }

    private fun report(flyer: Flyer, gigs: List<Gig>) {
        val read = gigs.associate { it.date to it.title.value }
        val expected = flyer.gigs.toMap()

        println("== ${flyer.month} == ${gigs.size} gig(s) read, ${expected.size} on the flyer")
        (read.keys + expected.keys).sortedBy { it.value }.forEach { date ->
            val wanted = expected[date]
            val got = read[date]
            when {
                wanted == null -> println("  + $date  not on the flyer: \"$got\"")
                got == null -> println("  - $date  missed: \"$wanted\"")
                sameWording(got, wanted) -> println("    $date  $got")
                else -> println("  ~ $date  read as \"$got\"\n              rather than \"$wanted\"")
            }
        }
        val elsewhere = gigs.filterNot { it.id.url.value.startsWith("https://www.facebook.com/thedevnw1#") }
        if (elsewhere.isNotEmpty()) println("  ! ${elsewhere.size} gig(s) not under the venue's page: ${elsewhere.map { it.id.url }}")
        println()
    }

    // A flyer prints a typographic apostrophe where a model transcribes a straight one, which is a
    // difference about type rather than about what the flyer says. Reported as a match so that a `~`
    // line always means a word was read wrongly - a diff that is noisy every run is one nobody reads.
    private fun sameWording(read: String, onTheFlyer: String) =
        read.replace('’', '\'') == onTheFlyer.replace('’', '\'')

    // The flyer as the source would meet it: one post carrying the real caption, and the image
    // itself. Faking the two requests rather than the source leaves the whole of it running for
    // real - which post the caption picks, the month a misread date is caught by, the karaoke night
    // dropped, the slashes settled, and the url each gig is given.
    private fun servingOnly(flyer: Flyer): HttpHandler = { request ->
        when {
            request.uri.host == "www.instagram.com" -> Response(OK).body(profilePayload(flyer))
            request.uri.toString() == flyer.imageUrl -> Response(OK).header("Content-Type", "image/jpeg")
                .body(flyer.file.inputStream(), flyer.file.length())
            else -> error("unexpected request: ${request.uri}")
        }
    }

    private fun profilePayload(flyer: Flyer) = """
        {"data":{"user":{"edge_owner_to_timeline_media":{"edges":[{"node":{
          "shortcode":"${flyer.shortcode}",
          "display_url":"${flyer.imageUrl}",
          "edge_media_to_caption":{"edges":[{"node":{"text":"${flyer.caption}"}}]}
        }}]}}}}
    """.trimIndent()

    private data class Flyer(
        val month: String,
        val shortcode: String,
        val caption: String,
        val gigs: List<Pair<GigDate, String>>,
    ) {
        val file = File("src/test/resources/flyers/$month.jpg")
        val imageUrl = "https://scontent.cdninstagram.com/$month.jpg"
    }

    private val flyers = listOf(
        // Three of June's rows wrap onto a second line, the continuation marked with a leading "+",
        // which is the shape that made one discarded prompt list a row twice, once per line. Its
        // post id is not recorded because this flyer came from a saved copy rather than the feed,
        // which only pages back about six weeks before Instagram stops answering.
        Flyer(
            "2026-06",
            "not-recorded",
            "What’s On JUNE 2026!",
            listOf(
                GigDate(2026, 6, 5) to "Contract Killer / Frayed Ends / RxPxC / NxFxG / Kill The Snitch",
                GigDate(2026, 6, 6) to "Concrete Age / Deity & Devilry +Devilsky / Onion Mash / Chewed Out",
                GigDate(2026, 6, 7) to "UK Death Dealers presents: Suffer / Gravery / The Slaughtering / Grave Torture",
                GigDate(2026, 6, 12) to "Mindpilot / Elentari + TBC",
                GigDate(2026, 6, 13) to "Urzah / Matter / Summerisle",
                GigDate(2026, 6, 19) to "Rattlesnakes / War Grave / Skrike",
                GigDate(2026, 6, 20) to "Them Bloody Kids / Confyde / +I Can’t Believe It’s Not Better / District 13",
            ),
        ),
        Flyer(
            "2026-07",
            "DaVb6UTKrM6",
            "What’s On JULY 2026!",
            listOf(
                GigDate(2026, 7, 4) to "Metal 2 The Masses Grand Final Afterparty",
                GigDate(2026, 7, 10) to "Dead Harts / Casket Feeder / Plagued",
                GigDate(2026, 7, 11) to "Symbyote / Cariad / The Slaughtering / Graveless",
                GigDate(2026, 7, 18) to "Regicide / Halberd / Mount Slatra",
                GigDate(2026, 7, 21) to "Rozemary / Kill The Snitch / Burn Pit Queen",
                GigDate(2026, 7, 25) to "Red Eyed Cult / Outback / Kushthulhu",
                GigDate(2026, 7, 31) to "Verminthrone / Deathfiend / By Demons be Driven",
            ),
        ),
        Flyer(
            "2026-08",
            "DbnmaB2Ck1N",
            "What’s On AUGUST 2026!",
            listOf(
                GigDate(2026, 8, 6) to "IRK / Why Patterns? / The Defamation Process",
                GigDate(2026, 8, 9) to "Subalternos(BRA) / Scandal",
                GigDate(2026, 8, 14) to "We Only Come Out At Night (Fundraiser): Servers of Hysteria / Hot Wife / Hamish The Brave",
                GigDate(2026, 8, 15) to "RETRIBUTION ALIVE presents: Technologist / Maxdmyz / Updownc / Maziac / Low Road",
                GigDate(2026, 8, 20) to "Mur (ISL) + Support TBA",
                GigDate(2026, 8, 21) to "Wailing Banshee / White Lightning",
                GigDate(2026, 8, 22) to "Underbelly Promotions presents: Liquified / Lobotomica / Disembowler / Malauriu",
                GigDate(2026, 8, 29) to "The Day of Locusts / Stour / Dungeon",
            ),
        ),
    )
}
