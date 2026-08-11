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
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GigClassifierTest {

    @Test
    fun `classifies gigs by scanning their event pages, skipping already classified ones`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { request ->
            requestCount++
            val body = if (request.uri.toString().endsWith("metal-gig")) "Doom metal night!" else "Comedy open mic"
            Response(OK).body(body)
        }
        val metalGig = GigEvent(title = "Doom Night", venue = "Some Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/metal-gig", imageUrl = "")
        val comedyGig = GigEvent(title = "Comedy Night", venue = "Some Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/comedy-gig", imageUrl = "")
        val oldGig = GigEvent(title = "Old Gig", venue = "Some Venue", year = 2026, month = "Aug", day = "10", url = "https://example.com/old-gig", imageUrl = "")
        val recordedAt = Instant.parse("2026-08-01T00:00:00Z")

        val classifications = classifyGigs(
            gigs = listOf(metalGig, comedyGig, oldGig),
            alreadyClassified = setOf(oldGig.id),
            classifyGig = { gig -> classifyGigByKeywords(fakeClient, gig, recordedAt) },
        )

        expectThat(requestCount).isEqualTo(2)
        expectThat(classifications).containsExactlyInAnyOrder(
            GigClassified(metalGig.venue, metalGig.url, recordedAt, genre = Genre.Metal, matchedKeywords = listOf("metal", "doom"), source = ClassificationSource.Keywords),
            GigClassified(comedyGig.venue, comedyGig.url, recordedAt, genre = Genre.Other, source = ClassificationSource.Keywords),
        )
    }

    @Test
    fun `limits classification to the soonest N not-yet-classified gigs`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("Comedy open mic") }
        val soonest = GigEvent(title = "Soonest Gig", venue = "Some Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/soonest", imageUrl = "")
        val middle = GigEvent(title = "Middle Gig", venue = "Some Venue", year = 2026, month = "Aug", day = "09", url = "https://example.com/middle", imageUrl = "")
        val latest = GigEvent(title = "Latest Gig", venue = "Some Venue", year = 2026, month = "Aug", day = "10", url = "https://example.com/latest", imageUrl = "")
        val recordedAt = Instant.parse("2026-08-01T00:00:00Z")

        val classifications = classifyGigs(
            gigs = listOf(latest, soonest, middle),
            alreadyClassified = emptySet(),
            limit = 2,
            classifyGig = { gig -> classifyGigByKeywords(fakeClient, gig, recordedAt) },
        )

        expectThat(requestCount).isEqualTo(2)
        expectThat(classifications.map { it.url }).containsExactly(soonest.url, middle.url)
    }

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
        val gig = GigEvent(title = "Some Gig", venue = "The Underworld", year = 2026, month = "Aug", day = "08", url = "https://example.com/gig", imageUrl = "")

        val classification = classifyGigByKeywords(fakeClient, gig, Instant.parse("2026-08-01T00:00:00Z"))

        expectThat(classification.matchedKeywords).containsExactly("metal", "doom")
    }

    @Test
    fun `fails fast when a venue's event page content can't be extracted`() {
        val fakeClient: HttpHandler = { Response(OK).body("<div>page markup changed, no article.event here</div>") }
        val gig = GigEvent(title = "Some Gig", venue = "The Underworld", year = 2026, month = "Aug", day = "08", url = "https://example.com/gig", imageUrl = "")

        val error = assertFailsWith<IllegalStateException> { classifyGigByKeywords(fakeClient, gig, Instant.parse("2026-08-01T00:00:00Z")) }

        expectThat(error.message!!.contains("The Underworld")).isTrue()
        expectThat(error.message!!.contains("https://example.com/gig")).isTrue()
    }

    @Test
    fun `scopes New Cross Inn classification to the client-rendered description attribute`() {
        val html = """
            <p x-ref="desc" x-html="'Doom metal night with support'"></p>
            <div>KINGS OF THRASH</div>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }
        val gig = GigEvent(title = "Some Gig", venue = "New Cross Inn", year = 2026, month = "Aug", day = "08", url = "https://example.com/gig", imageUrl = "")

        val classification = classifyGigByKeywords(fakeClient, gig, Instant.parse("2026-08-01T00:00:00Z"))

        expectThat(classification.matchedKeywords).containsExactly("metal", "doom")
    }

    private fun fakeChat(reply: String): Chat = Chat { _ ->
        Success(
            ChatResponse(
                Message.Assistant(listOf(Content.Text(reply))),
                ChatResponse.Metadata(ResponseId.of("fake-response-id"), ModelName.of("fake-model")),
            ),
        )
    }

    @Test
    fun `classifies a gig as Metal or Other based on the LLM chat's reply`() {
        val fakeClient: HttpHandler = { Response(OK).body("Some event page text") }
        val gig = GigEvent(title = "Some Gig", venue = "Some Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gig", imageUrl = "")
        val recordedAt = Instant.parse("2026-08-01T00:00:00Z")

        val metalClassification = classifyGigByLLM(fakeClient, fakeChat("Metal"), gig, recordedAt)
        val otherClassification = classifyGigByLLM(fakeClient, fakeChat("Other"), gig, recordedAt)

        expectThat(metalClassification).isEqualTo(GigClassified(gig.venue, gig.url, recordedAt, genre = Genre.Metal, source = ClassificationSource.LLM))
        expectThat(otherClassification).isEqualTo(GigClassified(gig.venue, gig.url, recordedAt, genre = Genre.Other, source = ClassificationSource.LLM))
    }

    @Test
    fun `falls back to the poster image with a vision model when event page text is too thin`() {
        val fakeClient: HttpHandler = { request ->
            if (request.uri.toString().endsWith("poster.jpg")) Response(OK).body("fake-image-bytes") else Response(OK).body("Thin")
        }
        var capturedRequest: ChatRequest? = null
        val chat = Chat { request ->
            capturedRequest = request
            Success(
                ChatResponse(
                    Message.Assistant(listOf(Content.Text("Metal"))),
                    ChatResponse.Metadata(ResponseId.of("fake-response-id"), ModelName.of("fake-model")),
                ),
            )
        }
        val gig = GigEvent(title = "Some Gig", venue = "Some Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gig", imageUrl = "https://example.com/poster.jpg")

        classifyGigByLLM(fakeClient, chat, gig, Instant.parse("2026-08-01T00:00:00Z"))

        val message = capturedRequest!!.messages.single() as Message.User
        expectThat(message.contents.filterIsInstance<Content.Image>()).hasSize(1)
        expectThat(capturedRequest!!.params.modelName).isEqualTo(ModelName.of("claude-sonnet-5"))
    }

    @Test
    fun `does not fetch the poster image when the event page text is long enough`() {
        var fetchedUrls = mutableListOf<String>()
        val fakeClient: HttpHandler = { request ->
            fetchedUrls.add(request.uri.toString())
            Response(OK).body("A".repeat(200))
        }
        var capturedRequest: ChatRequest? = null
        val chat = Chat { request ->
            capturedRequest = request
            Success(
                ChatResponse(
                    Message.Assistant(listOf(Content.Text("Other"))),
                    ChatResponse.Metadata(ResponseId.of("fake-response-id"), ModelName.of("fake-model")),
                ),
            )
        }
        val gig = GigEvent(title = "Some Gig", venue = "Some Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gig", imageUrl = "https://example.com/poster.jpg")

        classifyGigByLLM(fakeClient, chat, gig, Instant.parse("2026-08-01T00:00:00Z"))

        expectThat(fetchedUrls).containsExactly("https://example.com/gig")
        val message = capturedRequest!!.messages.single() as Message.User
        expectThat(message.contents.filterIsInstance<Content.Image>()).hasSize(0)
        expectThat(capturedRequest!!.params.modelName).isEqualTo(ModelName.of("claude-haiku-4-5-20251001"))
    }

    @Test
    fun `fails fast when the LLM chat replies with something other than a genre name`() {
        val fakeClient: HttpHandler = { Response(OK).body("Some event page text") }
        val gig = GigEvent(title = "Some Gig", venue = "Some Venue", year = 2026, month = "Aug", day = "08", url = "https://example.com/gig", imageUrl = "")

        val error = assertFailsWith<IllegalStateException> {
            classifyGigByLLM(fakeClient, fakeChat("I think this is probably a metal gig"), gig, Instant.parse("2026-08-01T00:00:00Z"))
        }

        expectThat(error.message!!.contains("Some Venue")).isTrue()
        expectThat(error.message!!.contains("https://example.com/gig")).isTrue()
    }
}
