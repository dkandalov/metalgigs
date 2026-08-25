package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.time.YearMonth
import kotlin.test.Test

// Why this reading surface and paging: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
// Why the copy is scoped this way: docs/adr/0007-a-description-is-the-gigs-own-copy.md
class OvoArenaGigsSourceTest {

    @Test
    fun `extracts music events from OVO Arena's month calendar, leaving its other categories out`() {
        val events = assertScrapesGigs(
            source = OvoArenaGigsSource(cachedClient(), from = YearMonth.of(2026, 8)),
            size = 40,
            first = Gig(
                GigId(ovoArena.id, GigUrl("https://www.ovoarena.co.uk/events/detail/stonebwoy#2026-08-15")),
                GigTitle("Stonebwoy"),
                GigDate(2026, 8, 15),
                PosterUrl("https://www.ovoarena.co.uk/assets/img/STONEBWOY-BHIM-FEST-LONDON-1440x810-c5b626371c.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(ovoArena.id, GigUrl("https://www.ovoarena.co.uk/events/detail/tash-sultana#2027-03-13")),
                GigTitle("RESCHEDULED DATE: Tash Sultana"),
                GigDate(2027, 3, 13),
                PosterUrl("https://www.ovoarena.co.uk/assets/img/Tash_2027_-1440x810-1ccd5e6573.jpg"),
                GigDescription(""),
            ),
        )

        expectThat(events.map { it.id.url }.distinct()).hasSize(40)
        // the eight months read held 59 events between them, so most of what the calendar returns is
        // dropped here - a filter that stopped filtering would show up as a much larger listing
        expectThat(events.filter { it.date.year == 2027 }).hasSize(6)
        // wrestling, comedy and a religious celebration all sit in those same months under another
        // category, and the drop-down's Music is what separates them
        expectThat(events.none { it.title.value.contains("Gladiators") || it.title.value.contains("Sunil Grover") }).isTrue()
    }

    // A month with nothing in it is not the end of the listing: read on 2026-08-17, this calendar had
    // no events at all in January 2027 and three in each of February and March.
    @Test
    fun `reads on past a month the OVO Arena calendar has nothing in`() {
        val events = OvoArenaGigsSource(cachedClient(), from = YearMonth.of(2026, 8)).latestGigs()

        expectThat(events.none { it.date.year == 2027 && it.date.monthValue == 1 }).isTrue()
        expectThat(events.filter { it.date.year == 2027 && it.date.monthValue == 3 }).hasSize(3)
    }

    // the age policy, the AXS ticket-transfer notice and the travel warning about the stadium next
    // door are all longer than some gigs' own copy, and none of them is about the act
    @Test
    fun `takes OVO Arena's gig copy without the venue's ticketing and travel notices`() {
        val html = """
            <div class="event_detail one_sidebar_right has_branding">
                <div class="ticketcontent">
                    <p>Find tickets Buy premium Date 04 Sep / 2026 Doors 18:00 Ticket Information</p>
                    <p>Age Restriction Standing: strictly 14+, with 14-15 year olds to be accompanied by an adult (16+)</p>
                    <p>For this show, if you&rsquo;ve purchased your tickets via AXS, you&rsquo;ll need to display your ticket on your phone.</p>
                    <p>Please note, there is a Bon Jovi concert taking place next door at the stadium on 4th September 2026.</p>
                    <p>There will be road closures in place around the area from early on.</p>
                </div>
                <div class="event_description expandable" data-options="event_detail" tabindex="0">
                    <p>The Neighbourhood is a California-based alternative rock band comprised of Jesse Rutherford and Zach Abels.</p>
                </div>
            </div>
        """.trimIndent()

        val pageText = OvoArenaGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("California-based alternative rock band")).isTrue()
        expectThat(pageText.contains("Age Restriction")).isEqualTo(false)
        expectThat(pageText.contains("AXS")).isEqualTo(false)
        expectThat(pageText.contains("road closures")).isEqualTo(false)
        expectThat(pageText.contains("Bon Jovi")).isEqualTo(false)
        expectThat(pageText.contains("Doors 18:00")).isEqualTo(false)
    }
}
