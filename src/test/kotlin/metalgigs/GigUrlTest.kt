package metalgigs

import strikt.api.expectThat
import strikt.assertions.contains
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GigUrlTest {

    @Test
    fun `refuses a blank url`() {
        assertFailsWith<IllegalArgumentException> { GigUrl("") }
    }

    @Test
    fun `refuses a blank url on a classification too`() {
        assertFailsWith<IllegalArgumentException> {
            GigClassified(GigId(VenueId("The Grace"), GigUrl("")), Instant.parse("2026-08-01T12:00:00Z"), Genre.Metal, ClassificationSource.User)
        }
    }

    @Test
    fun `refuses a blank url introduced by copy`() {
        val id = GigId(venueId = VenueId("The Grace"), url = GigUrl("https://example.com/gigs/a"))

        assertFailsWith<IllegalArgumentException> { id.copy(url = GigUrl("")) }
    }

    @Test
    fun `refuses a blank url read back from the log`() {
        val line = """{"_type": "classified", "seq": 0, "venue": "The Grace", "url": "", "recordedAt": "2026-08-01T12:00:00Z", "genre": "Metal", "source": "User"}"""

        // Kondor catches the failure and rethrows it as its own converter error, so the type is
        // theirs rather than IllegalArgumentException - the require's own message survives inside it
        val error = assertFailsWith<Exception> { JLogEntry.fromJson(line).orThrow() }

        expectThat(error.message!!).contains("A gig url can't be blank")
    }
}
