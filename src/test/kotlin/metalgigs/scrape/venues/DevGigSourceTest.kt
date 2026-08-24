package metalgigs.scrape.venues

import dev.forkhandles.result4k.Success
import metalgigs.*
import metalgigs.scrape.*
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.ChatResponse
import org.http4k.ai.llm.model.Content
import org.http4k.ai.llm.model.Message
import org.http4k.ai.model.ModelName
import org.http4k.ai.model.ResponseId
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo
import strikt.assertions.isNull
import strikt.assertions.isTrue
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DevGigSourceTest {

    // Verbatim what gemma4:26b replies to the August 2026 flyer, karaoke nights and all. What this
    // asserts is that the flyer is the post the caption picked out and that its gigs are built as
    // nights at The Dev's own page - not how well a model reads a picture.
    private val augustFlyerReply = """
        2026-08-06 | IRK/Why Patterns?/ The Defamation Process
        2026-08-09 | Subalternos(BRA)/Scandal
        2026-08-13 | RrroooaaarrR Rock/ Metal Karaoke
        2026-08-14 | We Only Come Out At Night (Fundraiser): Servers of Hysteria/ Hot Wife/ Hamish The Brave
        2026-08-15 | RETRIBUTION ALIVE presents: Technologist/Maxdmyz/Updownc/Maziac/ Low Road
        2026-08-20 | Mur (ISL) + Support TBA
        2026-08-21 | Wailing Banshee/White Lightning
        2026-08-22 | Underbelly Promotions presents: Liquified/Lobotomica/Disembowler/Malauriu
        2026-08-27 | RrroooaaarrR Rock/ Metal Karaoke
        2026-08-29 | The Day of Locusts/Stour/Dungeon
    """.trimIndent()

    @Test
    fun `reads a month of gigs off the flyer the account captions as its what's-on`() {
        val gigs = assertScrapesGigs(
            source = DevGigSource(clientWithStubbedFlyerImage(), fakeChat(augustFlyerReply)),
            size = 8,
            first = Gig(
                GigId(theDev.id, GigUrl("https://www.facebook.com/thedevnw1#gig-irk-why-patterns-the-defamation-process-2026-08-06")),
                GigTitle("IRK / Why Patterns? / The Defamation Process"),
                GigDate(2026, 8, 6),
                PosterUrl(augustFlyerImageUrl),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theDev.id, GigUrl("https://www.facebook.com/thedevnw1#gig-the-day-of-locusts-stour-dungeon-2026-08-29")),
                GigTitle("The Day of Locusts / Stour / Dungeon"),
                GigDate(2026, 8, 29),
                PosterUrl(augustFlyerImageUrl),
                GigDescription(""),
            ),
        )

        // the flyer is the whole listing, so every gig on it shares the one picture
        expectThat(gigs.map { it.posterUrl }.distinct()).containsExactly(PosterUrl(augustFlyerImageUrl))
        // and every one of them lives at the venue's page rather than at the post the flyer was
        // read off, which is what stops next month's flyer relisting them all under new urls
        expectThat(gigs.count { it.id.url.value.startsWith("https://www.facebook.com/thedevnw1#") }).isEqualTo(gigs.size)
        // and it is all the text there is, so a gig's title is also its description
        expectThat(gigs.map { it.description.value }).isEqualTo(gigs.map { it.title.value })
    }

    @Test
    fun `ignores blank lines and stray text around the model's gig lines`() {
        val reply = "Here's what I found:\n\n2026-08-14 | Doom Night\n\nThat's everything."

        expectThat(gigsFrom(reply).map { it.title }).containsExactly(GigTitle("Doom Night"))
    }

    // The Dev runs its karaoke night off the same flyer as its band shows, and nothing but the
    // title tells the two apart.
    @Test
    fun `excludes the recurring karaoke night, keeping the gigs around it`() {
        val reply = "2026-08-06 | Doom Night\n2026-08-13 | RrroooaaarrR Rock / Metal Karaoke\n2026-08-20 | Thrash Fest"

        expectThat(gigsFrom(reply).map { it.title }).containsExactly(GigTitle("Doom Night"), GigTitle("Thrash Fest"))
    }

    // The model spaces a bill's slashes differently from run to run, and the title is what a gig's
    // description and its url's slug are both built from, so the spacing it happens to pick is what
    // decides whether the same night reads as the same gig tomorrow.
    @Test
    fun `settles on one spacing for the slashes a bill is written with`() {
        val reply = "2026-08-22 | Liquified/Lobotomica/ Disembowler /Malauriu"

        val gig = gigsFrom(reply).single()

        expectThat(gig.title).isEqualTo(GigTitle("Liquified / Lobotomica / Disembowler / Malauriu"))
        expectThat(gig.description).isEqualTo(GigDescription("Liquified / Lobotomica / Disembowler / Malauriu"))
        // the slug reads through the spacing either way, so settling it doesn't relist the gig
        expectThat(gig.id.url).isEqualTo(GigUrl("https://www.facebook.com/thedevnw1#gig-liquified-lobotomica-disembowler-malauriu-2026-08-22"))
    }

    @Test
    fun `fails the venue's listing when no gigs can be read off the flyer`() {
        val error = assertFailsWith<IllegalStateException> { gigsFrom("I couldn't make out any dates on this poster.") }

        expectThat(error.message!!.contains("The Dev")).isTrue()
        expectThat(error.message!!.contains("2026-08")).isTrue()
    }

    @Test
    fun `drops a gig the model dated outside the month the caption names`() {
        val reply = "2026-08-06 | Doom Night\n2026-09-06 | Misread Night"

        expectThat(gigsFrom(reply).map { it.title }).containsExactly(GigTitle("Doom Night"))
    }

    // the flyer found in the recorded profile is August 2026's, so these replies are what a model
    // reading it might say, and a date outside that month is one it misread
    private fun gigsFrom(reply: String) = DevGigSource(clientWithStubbedFlyerImage(), fakeChat(reply)).latestGigs()

    @Test
    fun `reads the month off a what's-on caption, however the account punctuates it`() {
        expectThat(monthOfWhatsOnCaption("What's On AUGUST 2026! ALL EVENTS ARE FREE ENTRY!")).isEqualTo(YearMonth.of(2026, 8))
        expectThat(monthOfWhatsOnCaption("what’s on in September 2026")).isEqualTo(YearMonth.of(2026, 9))
        expectThat(monthOfWhatsOnCaption("Whats on December 2027 at The Dev")).isEqualTo(YearMonth.of(2027, 12))
    }

    // A band's own tour poster is what this has to refuse: The Dev reposts them, and they carry a
    // dated list in the same typography as the flyer - for nights at other venues.
    @Test
    fun `refuses a caption that isn't a month's what's-on`() {
        expectThat(monthOfWhatsOnCaption("SONGS OF PRAISE TOUR - New album Songs of Praise (out 22 Aug) is getting a proper send-off")).isNull()
        expectThat(monthOfWhatsOnCaption("@lobotomica_band tearing up @thedevcamden last night")).isNull()
        expectThat(monthOfWhatsOnCaption("What's on tonight: Wailing Banshee")).isNull()
    }

    private val augustFlyerImageUrl =
        "https://scontent-lhr6-2.cdninstagram.com/v/t51.82787-15/765997383_18481020796099389_1295098945150881137_n.jpg?stp=dst-jpg_e35_p1080x1080_sh2.08_tt6&_nc_ht=scontent-lhr6-2.cdninstagram.com&_nc_cat=109&_nc_oc=Q6cZ2gH_NeTi1lrv6w75f7K44smY9aHk7kJdYwe-Ep_h36N42z_ok6Ifb2GpP8Hx0bQEqOQ&_nc_ohc=VSihVTt6nTYQ7kNvwEfcfG3&_nc_gid=UMnKLPsafFbK5DrghQcH-w&edm=AOQ1c0wBAAAA&ccb=7-5&oh=00_AQGwDmL6HtziC2MfaSQ2SpG5NFUS1nfCFw4pAJM4kQDBtg&oe=6A927A84&_nc_sid=8b3546"

    // The flyer's bytes are only ever read by a model, and there is no model here - so they are
    // stubbed rather than recorded, which keeps half a megabyte of jpeg out of the fixtures. It
    // couldn't be recorded anyway: the traffic cache writes a response through bodyString(), which
    // a jpeg does not survive.
    private fun clientWithStubbedFlyerImage(): HttpHandler {
        val recorded = cachedClient()
        return { request ->
            if (request.uri.host.endsWith("cdninstagram.com")) Response(OK).header("Content-Type", "image/jpeg").body("stub-flyer-bytes")
            else recorded(request)
        }
    }

    private fun fakeChat(reply: String): Chat = Chat { _ ->
        Success(
            ChatResponse(
                Message.Assistant(listOf(Content.Text(reply))),
                ChatResponse.Metadata(ResponseId.of("fake-response-id"), ModelName.of("fake-model")),
            ),
        )
    }
}
