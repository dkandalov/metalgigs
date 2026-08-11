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
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
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

        val gigs = extractPosterGigs(fakeClient, fakeChat(reply), imageUrl = "https://example.com/poster.jpg", sourceUrl = "https://example.com/post/1", venue = "Some Venue")

        expectThat(gigs).containsExactly(
            GigEvent(title = "Doom Night", venue = "Some Venue", year = 2026, month = "Aug", day = "14", url = "https://example.com/post/1#gig-doom-night-2026-08-14", imageUrl = "https://example.com/poster.jpg"),
            GigEvent(title = "Thrash Fest", venue = "Some Venue", year = 2026, month = "Aug", day = "21", url = "https://example.com/post/1#gig-thrash-fest-2026-08-21", imageUrl = "https://example.com/poster.jpg"),
        )
    }

    @Test
    fun `ignores blank lines and stray text around the gig lines`() {
        val fakeClient: HttpHandler = { Response(OK).body("fake-poster-bytes") }
        val reply = "Here's what I found:\n\n2026-08-14 | Doom Night\n\nThat's everything."

        val gigs = extractPosterGigs(fakeClient, fakeChat(reply), imageUrl = "https://example.com/poster.jpg", sourceUrl = "https://example.com/post/1", venue = "Some Venue")

        expectThat(gigs.map { it.title }).containsExactly("Doom Night")
    }

    @Test
    fun `fails fast when no gigs can be parsed from the reply`() {
        val fakeClient: HttpHandler = { Response(OK).body("fake-poster-bytes") }

        val error = assertFailsWith<IllegalStateException> {
            extractPosterGigs(fakeClient, fakeChat("I couldn't make out any dates on this poster."), imageUrl = "https://example.com/poster.jpg", sourceUrl = "https://example.com/post/1", venue = "Some Venue")
        }

        expectThat(error.message!!.contains("Some Venue")).isTrue()
        expectThat(error.message!!.contains("https://example.com/poster.jpg")).isTrue()
    }
}
