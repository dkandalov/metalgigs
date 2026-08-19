import strikt.api.expectThat
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith

class VenueTest {

    @Test
    fun `refuses a blank id`() {
        assertFailsWith<IllegalArgumentException> { VenueId("") }
    }

    @Test
    fun `no two venues share an id`() {
        expectThat(allVenues.map { it.id }.distinct().size).isEqualTo(allVenues.size)
    }

    // rendering and every message naming a venue resolve a gig's VenueId through allVenues, so a
    // venue dropped from it while its gigs are still logged would fail the whole render
    @Test
    fun `every venue in the log is one of allVenues`() {
        val log = GigsLog(File("events.ndjson"))
        val logged = (log.currentGigs().map { it.id.venueId } + log.alreadyClassified().map { it.venueId }).distinct()

        expectThat(logged.filter { it !in allVenues.map { venue -> venue.id } }).isEmpty()
    }
}
