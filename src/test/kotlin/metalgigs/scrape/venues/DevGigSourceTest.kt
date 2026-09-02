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
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith

// Why the flyer is the listing: docs/adr/0011-the-devs-month-flyer-is-a-source-read-by-a-local-model.md
// Why titles are tidied as read: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
class DevGigSourceTest {

    // Verbatim what gemma4:26b replies to the September 2026 flyer, karaoke nights and all. What this
    // asserts is that the flyer is the post the caption picked out and that its gigs are built as
    // nights at The Dev's own page - not how well a model reads a picture.
    private val septemberFlyerReply = """
        2026-09-06 | IRK/Why Patterns?/ The Defamation Process
        2026-09-09 | Subalternos(BRA)/Scandal
        2026-09-13 | RrroooaaarrR Rock/ Metal Karaoke
        2026-09-14 | We Only Come Out At Night (Fundraiser): Servers of Hysteria/ Hot Wife/ Hamish The Brave
        2026-09-15 | RETRIBUTION ALIVE presents: Technologist/Maxdmyz/Updownc/Maziac/ Low Road
        2026-09-20 | Mur (ISL) + Support TBA
        2026-09-21 | Wailing Banshee/White Lightning
        2026-09-22 | Underbelly Promotions presents: Liquified/Lobotomica/Disembowler/Malauriu
        2026-09-27 | RrroooaaarrR Rock/ Metal Karaoke
        2026-09-29 | The Day of Locusts/Stour/Dungeon
    """.trimIndent()

    @Test
    fun `reads a month of gigs off the flyer the account captions as its what's-on`() {
        val gigs = assertScrapesGigs(
            source = DevGigSource(clientWithStubbedFlyerImage(), fakeChat(septemberFlyerReply)),
            size = 8,
            first = Gig(
                GigId(theDev.id, GigUrl("https://www.facebook.com/thedevnw1#gig-irk-why-patterns-the-defamation-process-2026-09-06")),
                GigTitle("IRK / Why Patterns? / The Defamation Process"),
                GigDate(2026, 9, 6),
                PosterUrl(septemberFlyerImageUrl),
                GigDescription(""),
            ),
            last = Gig(
                GigId(theDev.id, GigUrl("https://www.facebook.com/thedevnw1#gig-the-day-of-locusts-stour-dungeon-2026-09-29")),
                GigTitle("The Day of Locusts / Stour / Dungeon"),
                GigDate(2026, 9, 29),
                PosterUrl(septemberFlyerImageUrl),
                GigDescription(""),
            ),
        )

        // the flyer is the whole listing, so every gig on it shares the one picture
        expectThat(gigs.map { it.posterUrl }.distinct()).containsExactly(PosterUrl(septemberFlyerImageUrl))
        // and every one of them lives at the venue's page rather than at the post the flyer was
        // read off, which is what stops next month's flyer relisting them all under new urls
        expectThat(gigs.count { it.id.url.value.startsWith("https://www.facebook.com/thedevnw1#") }).isEqualTo(gigs.size)
        // and it is all the text there is, so a gig's title is also its description
        expectThat(gigs.map { it.description.value }).isEqualTo(gigs.map { it.title.value })
    }

    @Test
    fun `ignores blank lines and stray text around the model's gig lines`() {
        val reply = "Here's what I found:\n\n2026-09-14 | Doom Night\n\nThat's everything."

        expectThat(gigsFrom(reply).map { it.title }).containsExactly(GigTitle("Doom Night"))
    }

    // The Dev runs its karaoke night off the same flyer as its band shows, and nothing but the
    // title tells the two apart.
    @Test
    fun `excludes the recurring karaoke night, keeping the gigs around it`() {
        val reply = "2026-09-06 | Doom Night\n2026-09-13 | RrroooaaarrR Rock / Metal Karaoke\n2026-09-20 | Thrash Fest"

        expectThat(gigsFrom(reply).map { it.title }).containsExactly(GigTitle("Doom Night"), GigTitle("Thrash Fest"))
    }

    // The model spaces a bill's slashes differently from run to run, and the title is what a gig's
    // description and its url's slug are both built from, so the spacing it happens to pick is what
    // decides whether the same night reads as the same gig tomorrow.
    @Test
    fun `settles on one spacing for the slashes a bill is written with`() {
        val reply = "2026-09-22 | Liquified/Lobotomica/ Disembowler /Malauriu"

        val gig = gigsFrom(reply).single()

        expectThat(gig.title).isEqualTo(GigTitle("Liquified / Lobotomica / Disembowler / Malauriu"))
        expectThat(gig.description).isEqualTo(GigDescription("Liquified / Lobotomica / Disembowler / Malauriu"))
        // the slug reads through the spacing either way, so settling it doesn't relist the gig
        expectThat(gig.id.url).isEqualTo(GigUrl("https://www.facebook.com/thedevnw1#gig-liquified-lobotomica-disembowler-malauriu-2026-09-22"))
    }

    @Test
    fun `fails the venue's listing when no gigs can be read off the flyer`() {
        val error = assertFailsWith<IllegalStateException> { gigsFrom("I couldn't make out any dates on this poster.") }

        expectThat(error.message!!.contains("The Dev")).isTrue()
        expectThat(error.message!!.contains("2026-09")).isTrue()
    }

    @Test
    fun `drops a gig the model dated outside the month the caption names`() {
        val reply = "2026-09-06 | Doom Night\n2026-10-06 | Misread Night"

        expectThat(gigsFrom(reply).map { it.title }).containsExactly(GigTitle("Doom Night"))
    }

    // the flyer found in the recorded profile is September 2026's, so these replies are what a model
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

    private val septemberFlyerImageUrl =
        "https://scontent-lhr6-1.cdninstagram.com/v/t51.82787-15/793351264_18487194793099389_5079663409803739685_n.jpg?stp=dst-jpg_e35_s640x640_tt6&_nc_cat=103&ccb=7-5&_nc_sid=18de74&efg=eyJlZmdfdGFnIjoiRkVFRC5iZXN0X2ltYWdlX3VybGdlbi5DMyJ9&_nc_ohc=4sts_gwzz7MQ7kNvwGclIpC&_nc_oc=Adp3rbpGxgZX1UvY1gRyNVX6aI7JyM36uqadiGoddteDk0YqZzfL1_7UxsGxWzWtBLI&_nc_zt=23&_nc_ht=scontent-lhr6-1.cdninstagram.com&_nc_gid=6NXnXscNxLaivHZSw70jgg&_nc_ss=73689&oh=00_AQJofo0cfxYHf6H9erv2hnBD266PW3IHXvGLGgIIUPNWOA&oe=6A9E7D74"

    // The flyer's bytes are stubbed rather than recorded, which keeps half a megabyte of jpeg out of
    // the fixtures - and couldn't be recorded anyway, the traffic cache writing a response through
    // bodyString(), which a jpeg does not survive. They have to be a real picture even so: no model
    // reads them here, but ImageMagick enlarges them on the way to one, and any other bytes fail it.
    // an 8x8 black jpeg, which is all ImageMagick needs to succeed at enlarging one
    private val smallestJpeg =
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDACgcHiMeGSgjISMtKygwPGRBPDc3PHtYXUlkkYCZlo+AjIqgtObDoKrarYqMyP/L" +
            "2u71////m8H////6/+b9//j/wAALCAAIAAgBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAABf/EABQQAQAAAAAAAAAAAAAAAAAA" +
            "AAD/2gAIAQEAAD8AFf/Z"

    private fun clientWithStubbedFlyerImage(): HttpHandler {
        val recorded = cachedClient()
        return { request ->
            if (request.uri.host.endsWith("cdninstagram.com")) {
                Response(OK).header("Content-Type", "image/jpeg").body(Base64.getDecoder().decode(smallestJpeg).inputStream())
            }
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
