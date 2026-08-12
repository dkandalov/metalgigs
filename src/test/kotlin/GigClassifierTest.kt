import dev.forkhandles.result4k.Success
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.ChatRequest
import org.http4k.ai.llm.chat.ChatResponse
import org.http4k.ai.llm.model.Content
import org.http4k.ai.llm.model.Message
import org.http4k.ai.llm.model.Resource
import org.http4k.ai.model.ModelName
import org.http4k.connect.model.Base64Blob
import org.http4k.connect.model.MimeType
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

    private fun fakeChat(reply: String): Chat = Chat { _ -> chatResponse(reply) }

    private fun gig(title: String = "Some Gig", venue: String = "Some Venue", day: String = "08", url: String = "https://example.com/gig", imageUrl: String = "") =
        GigEvent(id = GigId(venue, url), title = title, year = 2026, month = "Aug", day = day, imageUrl = imageUrl)

    @Test
    fun `classifies a gig as Metal or Other from the LLM's reply`() {
        val fakeClient: HttpHandler = { Response(OK).body("Some event page text") }

        val metal = classifyGigByLLM(fakeClient, fakeChat("Metal"), gig(), recordedAt)
        val other = classifyGigByLLM(fakeClient, fakeChat("Other"), gig(), recordedAt)

        expectThat(metal).isEqualTo(GigClassified(gig().id, recordedAt, Genre.Metal, ClassificationSource.LLM))
        expectThat(other).isEqualTo(GigClassified(gig().id, recordedAt, Genre.Other, ClassificationSource.LLM))
    }

    @Test
    fun `uses page text captured at scrape time instead of refetching the event page`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("page text fetched now") }
        val (chat, requests) = capturingChat()

        classifyGigByLLM(fakeClient, chat, gig().copy(pageText = "text captured at scrape time"), recordedAt)

        expectThat(requestCount).isEqualTo(0)
        expectThat(requests.first().promptText().contains("text captured at scrape time")).isTrue()
    }

    @Test
    fun `falls back to fetching the event page for a gig observed before text was captured`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("page text fetched now") }
        val (chat, requests) = capturingChat()

        classifyGigByLLM(fakeClient, chat, gig().copy(pageText = null), recordedAt)

        expectThat(requestCount).isEqualTo(1)
        expectThat(requests.first().promptText().contains("page text fetched now")).isTrue()
    }

    @Test
    fun `skips gigs that are already classified`() {
        val alreadyDone = gig(title = "Already Done", url = "https://example.com/already-done")
        val toDo = gig(title = "To Do", day = "09", url = "https://example.com/to-do")
        var classified = 0

        val classifications = classifyGigs(
            gigs = listOf(alreadyDone, toDo),
            alreadyClassified = setOf(alreadyDone.id),
            classifyGig = { g -> classified++; GigClassified(g.id, recordedAt, Genre.Other, ClassificationSource.LLM) },
        )

        expectThat(classified).isEqualTo(1)
        expectThat(classifications.classified.map { it.id.url }).containsExactly(toDo.id.url)
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
            classifyGig = { g -> GigClassified(g.id, recordedAt, Genre.Other, ClassificationSource.LLM) },
        )

        expectThat(classifications.classified.map { it.id.url }).containsExactly(soonest.id.url, middle.id.url)
    }

    // a real run lost 50 gigs' worth of paid calls when the last one had a poster too big to send
    @Test
    fun `keeps the classifications made before and after one that fails`() {
        val first = gig(title = "First", day = "08", url = "https://example.com/first")
        val unjudgeable = gig(title = "Unjudgeable", day = "09", url = "https://example.com/unjudgeable")
        val last = gig(title = "Last", day = "10", url = "https://example.com/last")

        val run = classifyGigs(
            gigs = listOf(first, unjudgeable, last),
            alreadyClassified = emptySet(),
            classifyGig = { g ->
                check(g != unjudgeable) { "image too large" }
                GigClassified(g.id, recordedAt, Genre.Other, ClassificationSource.LLM)
            },
        )

        expectThat(run.classified.map { it.id.url }).containsExactly(first.id.url, last.id.url)
        expectThat(run.failed.map { (gig, reason) -> gig.title to reason })
            .containsExactly("Unjudgeable" to "image too large")
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

    // the real one downloads and runs the image through ImageMagick; these tests are about when the
    // vision path is taken and with which model, so they record the request and hand back a stub
    private fun stubPoster(requestedUrls: MutableList<String>): (HttpHandler, String) -> Content.Image =
        { _, url ->
            requestedUrls.add(url)
            Content.Image(Resource.Binary(Base64Blob.encode("fake-image-bytes".toByteArray()), MimeType.IMAGE_WEBP))
        }

    @Test
    fun `falls back to the poster image with a vision model when event page text is too thin`() {
        val fakeClient: HttpHandler = { Response(OK).body("Thin") }
        val (chat, requests) = capturingChat()
        val posterUrls = mutableListOf<String>()

        classifyGigByLLM(fakeClient, chat, gig(imageUrl = "https://example.com/poster.jpg"), recordedAt, stubPoster(posterUrls))

        expectThat(posterUrls).containsExactly("https://example.com/poster.jpg")
        val message = requests.first().messages.single() as Message.User
        expectThat(message.contents.filterIsInstance<Content.Image>()).hasSize(1)
        expectThat(requests.first().params.modelName).isEqualTo(ModelName.of("claude-sonnet-5"))
    }

    @Test
    fun `does not fetch the poster image when the event page text is long enough`() {
        val fetchedUrls = mutableListOf<String>()
        val fakeClient: HttpHandler = { request -> fetchedUrls.add(request.uri.toString()); Response(OK).body("A".repeat(200)) }
        val (chat, requests) = capturingChat()
        val posterUrls = mutableListOf<String>()

        classifyGigByLLM(fakeClient, chat, gig(imageUrl = "https://example.com/poster.jpg"), recordedAt, stubPoster(posterUrls))

        expectThat(fetchedUrls).containsExactly("https://example.com/gig")
        // not merely absent from the request - never fetched, so no download and no conversion
        expectThat(posterUrls).hasSize(0)
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

    @Test
    fun `reads the genre off the last line, so a caveat before the answer doesn't derail it`() {
        // verbatim from a real vision-path reply that used to fail the whole classify run
        val withCaveat = "I can't identify people in images. However, I can still classify this gig based on the title provided.\n\nOther"

        expectThat(genreFromReply(withCaveat)).isEqualTo(Genre.Other)
        expectThat(genreFromReply("Metal")).isEqualTo(Genre.Metal)
        expectThat(genreFromReply("  Other\n")).isEqualTo(Genre.Other)
        expectThat(genreFromReply("Metal.")).isEqualTo(Genre.Metal)
    }

    @Test
    fun `still rejects a reply whose answer line isn't just a genre`() {
        // the answer itself has to stand alone - a genre mentioned mid-sentence is too ambiguous to
        // trust (here the verdict is Other, but "metal" is the last genre word in the text)
        expectThat(genreFromReply("I think this is probably a metal gig")).isEqualTo(null)
        expectThat(genreFromReply("Not metal")).isEqualTo(null)
        expectThat(genreFromReply("")).isEqualTo(null)
    }
}
