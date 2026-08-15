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
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.time.Instant
import java.time.LocalDate
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

    private fun gig(title: String = "Some Gig", venue: VenueId = VenueId("Some Venue"), day: Int = 8, url: String = "https://example.com/gig", imageUrl: String = "", description: String = "") =
        Gig(id = GigId(venue, url), title = title, date = LocalDate.of(2026, 8, day), imageUrl = imageUrl, description = description)

    // Classifying makes no http request of its own - the only fetch it can do is the vision path's
    // poster, which the tests exercising that path stub out.
    private val noHttp: HttpHandler = { request -> error("unexpected http request: ${request.uri}") }

    @Test
    fun `classifies a gig as Metal or Other from the LLM's reply`() {
        val judgeable = gig(description = "Some event page text")

        val metal = classifyGigByLLM(noHttp, fakeChat("Metal"), judgeable, recordedAt)
        val other = classifyGigByLLM(noHttp, fakeChat("Other"), judgeable, recordedAt)

        // The fixture has no imageUrl, so this is the text path however thin the text is - records
        // which model judged it and confirms useVision = false rather than just leaving it null.
        val textModel = "claude-haiku-4-5-20251001"
        expectThat(metal).isEqualTo(GigClassified(judgeable.id, recordedAt, Genre.Metal, ClassificationSource.LLM, textModel, useVision = false))
        expectThat(other).isEqualTo(GigClassified(judgeable.id, recordedAt, Genre.Other, ClassificationSource.LLM, textModel, useVision = false))
    }

    @Test
    fun `judges a gig on the page text captured at scrape time`() {
        val (chat, requests) = capturingChat()

        classifyGigByLLM(noHttp, chat, gig(description = "text captured at scrape time"), recordedAt)

        expectThat(requests.first().promptText().contains("text captured at scrape time")).isTrue()
    }

    // Asking with nothing but a title gets an answer - Other - rather than an error, and no later run
    // reclassifies a gig that already has a verdict.
    @Test
    fun `refuses to classify a gig with neither page text nor a poster`() {
        val (chat, requests) = capturingChat()

        val error = assertFailsWith<IllegalStateException> {
            classifyGigByLLM(noHttp, chat, gig(description = "", imageUrl = ""), recordedAt)
        }

        expectThat(requests).hasSize(0)
        expectThat(error.message!!.contains("Some Venue")).isTrue()
        expectThat(error.message!!.contains("https://example.com/gig")).isTrue()
    }

    // The gigs the classifier refuses are collected like any other failure, so one of them costs its
    // own verdict and nothing else.
    @Test
    fun `a gig with nothing to judge stays Pending without sinking the run`() {
        val judgeable = gig(title = "Judgeable", description = "Doom metal night!", url = "https://example.com/judgeable")
        val blank = gig(title = "Blank", day = 9, url = "https://example.com/blank")

        val run = classifyGigs(gigs = listOf(judgeable, blank), alreadyClassified = emptySet()) { g ->
            classifyGigByLLM(noHttp, fakeChat("Metal"), g, recordedAt)
        }

        expectThat(run.classified.map { it.id.url }).containsExactly(judgeable.id.url)
        expectThat(run.failed.map { (gig, _) -> gig.title }).containsExactly("Blank")
    }

    @Test
    fun `skips gigs that are already classified`() {
        val alreadyDone = gig(title = "Already Done", url = "https://example.com/already-done")
        val toDo = gig(title = "To Do", day = 9, url = "https://example.com/to-do")
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
        val soonest = gig(title = "Soonest", day = 8, url = "https://example.com/soonest")
        val middle = gig(title = "Middle", day = 9, url = "https://example.com/middle")
        val latest = gig(title = "Latest", day = 10, url = "https://example.com/latest")

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
        val first = gig(title = "First", day = 8, url = "https://example.com/first")
        val unjudgeable = gig(title = "Unjudgeable", day = 9, url = "https://example.com/unjudgeable")
        val last = gig(title = "Last", day = 10, url = "https://example.com/last")

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

    private fun capturingChat(): Pair<Chat, MutableList<ChatRequest>> {
        val requests = mutableListOf<ChatRequest>()
        return Chat { request -> requests.add(request); chatResponse("Other") } to requests
    }

    private fun ChatRequest.promptText() =
        (messages.single() as Message.User).contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    // the real one downloads and runs the image through ImageMagick; these tests are about when the
    // vision path is taken and with which model, so they record the request and hand back a stub
    private fun stubPoster(requestedUrls: MutableList<String>): (HttpHandler, String) -> Content.Image =
        { _, url ->
            requestedUrls.add(url)
            Content.Image(Resource.Binary(Base64Blob.encode("fake-image-bytes".toByteArray()), MimeType.IMAGE_WEBP))
        }

    @Test
    fun `falls back to the poster image with a vision model when event page text is too thin`() {
        val (chat, requests) = capturingChat()
        val posterUrls = mutableListOf<String>()
        val thin = gig(imageUrl = "https://example.com/poster.jpg", description = "Thin")

        val classified = classifyGigByLLM(noHttp, chat, thin, recordedAt, stubPoster(posterUrls))

        expectThat(posterUrls).containsExactly("https://example.com/poster.jpg")
        val message = requests.first().messages.single() as Message.User
        expectThat(message.contents.filterIsInstance<Content.Image>()).hasSize(1)
        expectThat(requests.first().params.modelName).isEqualTo(ModelName.of("claude-sonnet-5"))
        expectThat(classified.useVision).isEqualTo(true)
        expectThat(classified.llmModel).isEqualTo("claude-sonnet-5")
    }

    @Test
    fun `does not fetch the poster image when the event page text is long enough`() {
        val (chat, requests) = capturingChat()
        val posterUrls = mutableListOf<String>()
        val described = gig(imageUrl = "https://example.com/poster.jpg", description = "A".repeat(200))

        val classified = classifyGigByLLM(noHttp, chat, described, recordedAt, stubPoster(posterUrls))

        // not merely absent from the request - never fetched, so no download and no conversion
        expectThat(posterUrls).hasSize(0)
        val message = requests.first().messages.single() as Message.User
        expectThat(message.contents.filterIsInstance<Content.Image>()).hasSize(0)
        expectThat(requests.first().params.modelName).isEqualTo(ModelName.of("claude-haiku-4-5-20251001"))
        expectThat(classified.useVision).isEqualTo(false)
        expectThat(classified.llmModel).isEqualTo("claude-haiku-4-5-20251001")
    }

    @Test
    fun `fails fast when the LLM chat replies with something other than a genre name`() {
        val error = assertFailsWith<IllegalStateException> {
            classifyGigByLLM(noHttp, fakeChat("I think this is probably a metal gig"), gig(description = "Some event page text"), recordedAt)
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
