import strikt.api.expectThat
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import java.io.File
import kotlin.test.Test

class VenueTest {

    @Test
    fun `no two venues share an id`() {
        expectThat(allVenues.map { it.id }.distinct().size).isEqualTo(allVenues.size)
    }

    // rendering and every message naming a venue resolve a gig's VenueId through allVenues, so a
    // venue dropped from it while its gigs are still logged would fail the whole render
    @Test
    fun `every venue in the log is one of allVenues`() {
        val logged = readLogEntries(File("events.ndjson")).mapNotNull {
            when (it) {
                is GigObserved -> it.gig.id.venueId
                is GigClassified -> it.id.venueId
                is GigsRendered -> null
            }
        }.distinct()

        expectThat(logged.filter { it !in allVenues.map { venue -> venue.id } }).isEmpty()
    }
}
