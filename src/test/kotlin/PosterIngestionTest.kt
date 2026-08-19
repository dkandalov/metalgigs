import dev.forkhandles.result4k.Success
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
import strikt.assertions.isTrue
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PosterIngestionTest {

    private fun fakeChat(reply: String): Chat = Chat { _ ->
        Success(
            ChatResponse(
                Message.Assistant(listOf(Content.Text(reply))),
                ChatResponse.Metadata(ResponseId.of("fake-response-id"), ModelName.of("fake-model")),
            ),
        )
    }

    @Test
    fun `extracts one gig per line from the poster reply`() {
        val fakeClient: HttpHandler = { Response(OK).body("fake-poster-bytes") }
        val reply = "2026-08-14 | Doom Night\n2026-08-21 | Thrash Fest"

        val gigs = extractPosterGigs(fakeClient, fakeChat(reply), imageUrl = "https://example.com/poster.jpg", sourceUrl = "https://example.com/post/1", venue = Venue(VenueId("some-venue"), "Some Venue"))

        // the poster is the only source there is, so a gig's title is also all its text
        expectThat(gigs).containsExactly(
            Gig(id = GigId(VenueId("some-venue"), "https://example.com/post/1#gig-doom-night-2026-08-14"), title = GigTitle("Doom Night"), date = LocalDate.of(2026, 8, 14), posterUrl = PosterUrl("https://example.com/poster.jpg"), description = GigDescription("Doom Night")),
            Gig(id = GigId(VenueId("some-venue"), "https://example.com/post/1#gig-thrash-fest-2026-08-21"), title = GigTitle("Thrash Fest"), date = LocalDate.of(2026, 8, 21), posterUrl = PosterUrl("https://example.com/poster.jpg"), description = GigDescription("Thrash Fest")),
        )
    }

    @Test
    fun `ignores blank lines and stray text around the gig lines`() {
        val fakeClient: HttpHandler = { Response(OK).body("fake-poster-bytes") }
        val reply = "Here's what I found:\n\n2026-08-14 | Doom Night\n\nThat's everything."

        val gigs = extractPosterGigs(fakeClient, fakeChat(reply), imageUrl = "https://example.com/poster.jpg", sourceUrl = "https://example.com/post/1", venue = Venue(VenueId("some-venue"), "Some Venue"))

        expectThat(gigs.map { it.title.value }).containsExactly("Doom Night")
    }

    @Test
    fun `excludes The Dev's recurring karaoke night, keeping other gigs`() {
        val fakeClient: HttpHandler = { Response(OK).body("fake-poster-bytes") }
        val reply = "2026-08-06 | Doom Night\n2026-08-13 | RrroooaaarrR Rock/Metal Karaoke\n2026-08-20 | Thrash Fest"

        val gigs = extractPosterGigs(fakeClient, fakeChat(reply), imageUrl = "https://example.com/poster.jpg", sourceUrl = "https://example.com/post/1", venue = theDev)

        expectThat(gigs.map { it.title.value }).containsExactly("Doom Night", "Thrash Fest")
    }

    @Test
    fun `does not exclude karaoke-titled gigs at other venues`() {
        val fakeClient: HttpHandler = { Response(OK).body("fake-poster-bytes") }
        val reply = "2026-08-13 | Metal Karaoke Night"

        val gigs = extractPosterGigs(fakeClient, fakeChat(reply), imageUrl = "https://example.com/poster.jpg", sourceUrl = "https://example.com/post/1", venue = Venue(VenueId("some-other-venue"), "Some Other Venue"))

        expectThat(gigs.map { it.title.value }).containsExactly("Metal Karaoke Night")
    }

    @Test
    fun `fails fast when no gigs can be parsed from the reply`() {
        val fakeClient: HttpHandler = { Response(OK).body("fake-poster-bytes") }

        val error = assertFailsWith<IllegalStateException> {
            extractPosterGigs(fakeClient, fakeChat("I couldn't make out any dates on this poster."), imageUrl = "https://example.com/poster.jpg", sourceUrl = "https://example.com/post/1", venue = Venue(VenueId("some-venue"), "Some Venue"))
        }

        expectThat(error.message!!.contains("Some Venue")).isTrue()
        expectThat(error.message!!.contains("https://example.com/poster.jpg")).isTrue()
    }
}
