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

    private fun gig(title: String = "Some Gig", venue: Venue = Venue("Some Venue"), day: Int = 8, url: String = "https://example.com/gig", imageUrl: String = "") =
        Gig(id = GigId(venue, url), title = title, date = LocalDate.of(2026, 8, day), imageUrl = imageUrl)

    @Test
    fun `classifies a gig as Metal or Other from the LLM's reply`() {
        val fakeClient: HttpHandler = { Response(OK).body("Some event page text") }

        val metal = classifyGigByLLM(fakeClient, fakeChat("Metal"), gig(), recordedAt)
        val other = classifyGigByLLM(fakeClient, fakeChat("Other"), gig(), recordedAt)

        // The fixture has no imageUrl, so this is the text path however thin the text is - records
        // which model judged it and confirms useVision = false rather than just leaving it null.
        val textModel = "claude-haiku-4-5-20251001"
        expectThat(metal).isEqualTo(GigClassified(gig().id, recordedAt, Genre.Metal, ClassificationSource.LLM, textModel, useVision = false))
        expectThat(other).isEqualTo(GigClassified(gig().id, recordedAt, Genre.Other, ClassificationSource.LLM, textModel, useVision = false))
    }

    @Test
    fun `uses page text captured at scrape time instead of refetching the event page`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("page text fetched now") }
        val (chat, requests) = capturingChat()

        classifyGigByLLM(fakeClient, chat, gig().copy(description = "text captured at scrape time"), recordedAt)

        expectThat(requestCount).isEqualTo(0)
        expectThat(requests.first().promptText().contains("text captured at scrape time")).isTrue()
    }

    @Test
    fun `falls back to fetching the event page for a gig observed before text was captured`() {
        var requestCount = 0
        val fakeClient: HttpHandler = { requestCount++; Response(OK).body("page text fetched now") }
        val (chat, requests) = capturingChat()

        classifyGigByLLM(fakeClient, chat, gig().copy(description = ""), recordedAt)

        expectThat(requestCount).isEqualTo(1)
        expectThat(requests.first().promptText().contains("page text fetched now")).isTrue()
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

        classifyGigByLLM(fakeClient, chat, gig(venue = theUnderworld), recordedAt)

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

        classifyGigByLLM(fakeClient, chat, gig(venue = newCrossInn), recordedAt)

        expectThat(requests.first().promptText().contains("Doom metal night with support")).isTrue()
        expectThat(requests.first().promptText().contains("KINGS OF THRASH")).isEqualTo(false)
    }

    // two different kinds of boilerplate reach the whole page: the sitewide nav ("Summer Season",
    // "Food And Drink") outside #event_content, and a sidebar of generic quick-link buttons ("Buy
    // Tickets", "FAQs", ...) *inside* it - the second one only turned up against the real site,
    // after #event_content alone looked like enough of a fix
    @Test
    fun `scopes Alexandra Palace classification to the description and key-information accordion`() {
        val html = """
            <nav><li>Summer Season</li><li>Food And Drink</li></nav>
            <div id="event_content">
                <div class="event_sidebar"><ul class="event_buttons"><li>Buy Tickets</li><li>FAQs</li></ul></div>
                <div class="ap_text_block"><p>Doom metal night!</p></div>
                <div id="key-information"><h3>Key information</h3><p>Support from Kings of Thrash.</p></div>
            </div>
        """.trimIndent()
        val fakeClient: HttpHandler = { Response(OK).body(html) }
        val (chat, requests) = capturingChat()

        classifyGigByLLM(fakeClient, chat, gig(venue = alexandraPalace), recordedAt)

        val promptText = requests.first().promptText()
        expectThat(promptText.contains("Doom metal night!")).isTrue()
        expectThat(promptText.contains("Kings of Thrash")).isTrue()
        expectThat(promptText.contains("Summer Season")).isEqualTo(false)
        expectThat(promptText.contains("Buy Tickets")).isEqualTo(false)
    }

    @Test
    fun `fails fast when a venue's event page content can't be extracted`() {
        val fakeClient: HttpHandler = { Response(OK).body("<div>page markup changed, no article.event here</div>") }

        val error = assertFailsWith<IllegalStateException> {
            classifyGigByLLM(fakeClient, fakeChat("Other"), gig(venue = theUnderworld), recordedAt)
        }

        expectThat(error.message!!.contains("The Underworld")).isTrue()
        expectThat(error.message!!.contains("https://example.com/gig")).isTrue()
    }

    @Test
    fun `flags a venue whose gigs share a long stretch of boilerplate text`() {
        val boilerplate = "Sign up for news, offers and events at our venue today"
        val gigs = listOf(
            gig(title = "A", url = "https://example.com/a").copy(description = "Doom metal night with support. $boilerplate"),
            gig(title = "B", url = "https://example.com/b").copy(description = "Thrash revival show tonight. $boilerplate"),
            gig(title = "C", url = "https://example.com/c").copy(description = "Black metal ritual returns. $boilerplate"),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(mapOf(Venue("Some Venue") to 3))
    }

    // real venues often print the same short policy line (age restriction, ID requirement) on every
    // event page as genuine content - that alone isn't the sitewide-nav-and-footer bug this looks for
    @Test
    fun `does not flag a venue whose gigs merely share a short disclaimer within much longer unique text`() {
        val disclaimer = "Under 18s must be accompanied by an adult at all times"
        val gigs = listOf(
            gig(title = "A", url = "https://example.com/a").copy(description = "unique-a ".repeat(100) + disclaimer),
            gig(title = "B", url = "https://example.com/b").copy(description = "unique-b ".repeat(100) + disclaimer),
            gig(title = "C", url = "https://example.com/c").copy(description = "unique-c ".repeat(100) + disclaimer),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(emptyMap())
    }

    @Test
    fun `does not flag a venue whose gigs have genuinely distinct descriptions`() {
        val gigs = listOf(
            gig(title = "A", url = "https://example.com/a").copy(description = "Doom metal night with support from local acts"),
            gig(title = "B", url = "https://example.com/b").copy(description = "Thrash revival show featuring three touring bands"),
            gig(title = "C", url = "https://example.com/c").copy(description = "Black metal ritual with atmospheric visuals tonight"),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(emptyMap())
    }

    @Test
    fun `does not flag a venue with too few gigs to tell a coincidence from real contamination`() {
        val boilerplate = "Sign up for news, offers and events at our venue today"
        val gigs = listOf(
            gig(title = "A", url = "https://example.com/a").copy(description = "Doom metal night. $boilerplate"),
            gig(title = "B", url = "https://example.com/b").copy(description = "Thrash revival show. $boilerplate"),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(emptyMap())
    }

    @Test
    fun `ignores gigs with no captured description, including toward the minimum gig count`() {
        val boilerplate = "Sign up for news, offers and events at our venue today"
        val gigs = listOf(
            gig(title = "A", url = "https://example.com/a").copy(description = "Doom metal night. $boilerplate"),
            gig(title = "B", url = "https://example.com/b").copy(description = "Thrash revival show. $boilerplate"),
            gig(title = "C", url = "https://example.com/c").copy(description = ""),
        )

        expectThat(likelyContaminatedVenues(gigs)).isEqualTo(emptyMap())
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

        val classified = classifyGigByLLM(fakeClient, chat, gig(imageUrl = "https://example.com/poster.jpg"), recordedAt, stubPoster(posterUrls))

        expectThat(posterUrls).containsExactly("https://example.com/poster.jpg")
        val message = requests.first().messages.single() as Message.User
        expectThat(message.contents.filterIsInstance<Content.Image>()).hasSize(1)
        expectThat(requests.first().params.modelName).isEqualTo(ModelName.of("claude-sonnet-5"))
        expectThat(classified.useVision).isEqualTo(true)
        expectThat(classified.llmModel).isEqualTo("claude-sonnet-5")
    }

    @Test
    fun `does not fetch the poster image when the event page text is long enough`() {
        val fetchedUrls = mutableListOf<String>()
        val fakeClient: HttpHandler = { request -> fetchedUrls.add(request.uri.toString()); Response(OK).body("A".repeat(200)) }
        val (chat, requests) = capturingChat()
        val posterUrls = mutableListOf<String>()

        val classified = classifyGigByLLM(fakeClient, chat, gig(imageUrl = "https://example.com/poster.jpg"), recordedAt, stubPoster(posterUrls))

        expectThat(fetchedUrls).containsExactly("https://example.com/gig")
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
