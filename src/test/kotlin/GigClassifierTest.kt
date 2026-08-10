import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
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
            fakeClient,
            gigs = listOf(metalGig, comedyGig, oldGig),
            alreadyClassified = setOf(oldGig.venue to oldGig.url),
            recordedAt = recordedAt,
        )

        expectThat(requestCount).isEqualTo(2)
        expectThat(classifications).containsExactlyInAnyOrder(
            GigClassified(metalGig.venue, metalGig.url, recordedAt, genre = Genre.Metal, matchedKeywords = listOf("metal", "doom"), source = ClassificationSource.Keywords),
            GigClassified(comedyGig.venue, comedyGig.url, recordedAt, genre = Genre.Unclassified, source = ClassificationSource.Keywords),
        )
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

        val classification = classifyGig(fakeClient, gig, Instant.parse("2026-08-01T00:00:00Z"))

        expectThat(classification.matchedKeywords).containsExactly("metal", "doom")
    }

    @Test
    fun `fails fast when a venue's event page content can't be extracted`() {
        val fakeClient: HttpHandler = { Response(OK).body("<div>page markup changed, no article.event here</div>") }
        val gig = GigEvent(title = "Some Gig", venue = "The Underworld", year = 2026, month = "Aug", day = "08", url = "https://example.com/gig", imageUrl = "")

        val error = assertFailsWith<IllegalStateException> { classifyGig(fakeClient, gig, Instant.parse("2026-08-01T00:00:00Z")) }

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

        val classification = classifyGig(fakeClient, gig, Instant.parse("2026-08-01T00:00:00Z"))

        expectThat(classification.matchedKeywords).containsExactly("metal", "doom")
    }
}
