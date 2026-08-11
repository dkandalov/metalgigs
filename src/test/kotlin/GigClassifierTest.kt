import dev.forkhandles.result4k.Success
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.ChatRequest
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
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GigClassifierTest {

    private val recordedAt = Instant.parse("2026-08-01T00:00:00Z")

    private fun chatResponse(reply: String) = Success(
        ChatResponse(
            Message.Assistant(listOf(Content.Text(reply))),
            ChatResponse.Metadata(ResponseId.of("fake-response-id"), ModelName.of("fake-model")),
        ),
    )

    // replies are handed out in order, so a test can make the samples agree or disagree; the last
    // one repeats once exhausted, which keeps single-reply cases readable
    private fun fakeChat(vararg replies: String): Chat {
        var call = 0
        return Chat { _ -> chatResponse(replies[minOf(call++, replies.size - 1)]) }
    }

    private fun gig(title: String = "Some Gig", venue: String = "Some Venue", day: String = "08", url: String = "https://example.com/gig", imageUrl: String = "") =
        GigEvent(title = title, venue = venue, year = 2026, month = "Aug", day = day, url = url, imageUrl = imageUrl)

    @Test
    fun `classifies a gig when repeated samples agree`() {
        val fakeClient: HttpHandler = { Response(OK).body("Some event page text") }

        val metal = classifyGigByLLM(fakeClient, fakeChat("Metal", "Metal"), gig(), recordedAt)
        val other = classifyGigByLLM(fakeClient, fakeChat("Other", "Other"), gig(), recordedAt)

        expectThat(metal.genre).isEqualTo(Genre.Metal)
        expectThat(metal.sampledGenres).containsExactly(Genre.Metal, Genre.Metal)
        expectThat(metal.source).isEqualTo(ClassificationSource.LLM)
        expectThat(other.genre).isEqualTo(Genre.Other)
        expectThat(other.sampledGenres).containsExactly(Genre.Other, Genre.Other)
    }

    @Test
    fun `records both verdicts when repeated samples disagree`() {
        val fakeClient: HttpHandler = { Response(OK).body("Some event page text") }

        val classification = classifyGigByLLM(fakeClient, fakeChat("Metal", "Other"), gig(), recordedAt)

        expectThat(classification.sampledGenres).containsExactly(Genre.Metal, Genre.Other)
    }

    @Test
    fun `skips gigs that are already classified`() {
        val alreadyDone = gig(title = "Already Done", url = "https://example.com/already-done")
        val toDo = gig(title = "To Do", day = "09", url = "https://example.com/to-do")
        var classified = 0

        val classifications = classifyGigs(
            gigs = listOf(alreadyDone, toDo),
            alreadyClassified = setOf(alreadyDone.id),
            classifyGig = { g -> classified++; GigClassified(g.venue, g.url, recordedAt, Genre.Other, ClassificationSource.LLM) },
        )

        expectThat(classified).isEqualTo(1)
        expectThat(classifications.map { it.url }).containsExactly(toDo.url)
    }

    @Test
    fun `limits classification to the soonest N not-yet-classified gigs`() {
        val soonest = gig(title = "Soonest", day = "08", url = "https://example.com/soonest")
        val middle = gig(title = "Middle", day = "09", url = "https://example.com/middle")
        val latest = gig(title = "Latest", day = "10", url = "https://example.com/latest")

        val classifications = classifyGigs(
            gigs = listOf(latest, soonest, middle),
            alreadyClassified = emptySet(),
            limit = 2,
            classifyGig = { g -> GigClassified(g.venue, g.url, recordedAt, Genre.Other, ClassificationSource.LLM) },
        )

        expectThat(classifications.map { it.url }).containsExactly(soonest.url, middle.url)
    }

    // the venue-specific page-content extraction below feeds whatever the classifier sees, so these
    // assert on the text that actually reached the model rather than on the verdict it returned
    private fun capturingChat(): Pair<Chat, MutableList<ChatRequest>> {
        val requests = mutableListOf<ChatRequest>()
        return Chat { request -> requests.add(request); chatResponse("Other") } to requests
    }

    private fun ChatRequest.promptText() =
        (messages.single() as Message.User).contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    @Test
    fun `scopes The Underworld classification to the gig's own content, ignoring other-events widgets`() {
        val html = """
            <article class="event">
              <div class="content"><p>Doom metal night!</p></div>
            </article>
            <article class="list">
              <h3 class="list-header-title">KINGS OF THRASH</h3>
            </article>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }
        val (chat, requests) = capturingChat()

        classifyGigByLLM(fakeClient, chat, gig(venue = "The Underworld"), recordedAt)

        expectThat(requests.first().promptText().contains("Doom metal night!")).isTrue()
        expectThat(requests.first().promptText().contains("KINGS OF THRASH")).isEqualTo(false)
    }

    @Test
    fun `scopes New Cross Inn classification to the client-rendered description attribute`() {
        val html = """
            <p x-ref="desc" x-html="'Doom metal night with support'"></p>
            <div>KINGS OF THRASH</div>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }
        val (chat, requests) = capturingChat()

        classifyGigByLLM(fakeClient, chat, gig(venue = "New Cross Inn"), recordedAt)

        expectThat(requests.first().promptText().contains("Doom metal night with support")).isTrue()
        expectThat(requests.first().promptText().contains("KINGS OF THRASH")).isEqualTo(false)
    }

    @Test
    fun `fails fast when a venue's event page content can't be extracted`() {
        val fakeClient: HttpHandler = { Response(OK).body("<div>page markup changed, no article.event here</div>") }

        val error = assertFailsWith<IllegalStateException> {
            classifyGigByLLM(fakeClient, fakeChat("Other"), gig(venue = "The Underworld"), recordedAt)
        }

        expectThat(error.message!!.contains("The Underworld")).isTrue()
        expectThat(error.message!!.contains("https://example.com/gig")).isTrue()
    }

    @Test
    fun `falls back to the poster image with a vision model when event page text is too thin`() {
        val fakeClient: HttpHandler = { request ->
            if (request.uri.toString().endsWith("poster.jpg")) Response(OK).body("fake-image-bytes") else Response(OK).body("Thin")
        }
        val (chat, requests) = capturingChat()

        classifyGigByLLM(fakeClient, chat, gig(imageUrl = "https://example.com/poster.jpg"), recordedAt)

        val message = requests.first().messages.single() as Message.User
        expectThat(message.contents.filterIsInstance<Content.Image>()).hasSize(1)
        expectThat(requests.first().params.modelName).isEqualTo(ModelName.of("claude-sonnet-5"))
    }

    @Test
    fun `does not fetch the poster image when the event page text is long enough`() {
        val fetchedUrls = mutableListOf<String>()
        val fakeClient: HttpHandler = { request -> fetchedUrls.add(request.uri.toString()); Response(OK).body("A".repeat(200)) }
        val (chat, requests) = capturingChat()

        classifyGigByLLM(fakeClient, chat, gig(imageUrl = "https://example.com/poster.jpg"), recordedAt)

        expectThat(fetchedUrls).containsExactly("https://example.com/gig")
        val message = requests.first().messages.single() as Message.User
        expectThat(message.contents.filterIsInstance<Content.Image>()).hasSize(0)
        expectThat(requests.first().params.modelName).isEqualTo(ModelName.of("claude-haiku-4-5-20251001"))
    }

    @Test
    fun `fails fast when the LLM chat replies with something other than a genre name`() {
        val fakeClient: HttpHandler = { Response(OK).body("Some event page text") }

        val error = assertFailsWith<IllegalStateException> {
            classifyGigByLLM(fakeClient, fakeChat("I think this is probably a metal gig"), gig(), recordedAt)
        }

        expectThat(error.message!!.contains("Some Venue")).isTrue()
        expectThat(error.message!!.contains("https://example.com/gig")).isTrue()
    }
}
