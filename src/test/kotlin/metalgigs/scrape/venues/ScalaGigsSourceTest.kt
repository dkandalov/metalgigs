package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.time.LocalDate
import kotlin.test.Test

class ScalaGigsSourceTest {

    @Test
    fun `extracts gig events from Scala's live music category page, following pagination`() {
        val events = assertScrapesGigs(
            source = ScalaGigsSource(cachedClient()),
            size = 55,
            first = Gig(
                GigId(scala.id, "https://scala.co.uk/events/digable-planets/"),
                GigTitle("Digable Planets"),
                LocalDate.of(2026, 8, 19),
                PosterUrl("https://scala.co.uk/s/wp-content/uploads/2026/03/Digable-Planets-2026_colour-c-Emilio-Herce-scaled-e1774636627462.jpeg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(scala.id, "https://scala.co.uk/events/split-the-dealer-deva-st-john/"),
                GigTitle("SPLIT THE DEALER & DEVA ST.JOHN"),
                LocalDate.of(2027, 5, 20),
                PosterUrl("https://scala.co.uk/s/wp-content/uploads/2026/05/Scala-poster-Prf2_page-0001-1-e1779370004481.jpg"),
                GigDescription(""),
            ),
            urlPrefix = "https://scala.co.uk/events/",
        )

        // 36 on the first page, 19 on the second - a size assertion alone wouldn't catch double
        // counting if a future site change made the "next" link loop back to page 1
        expectThat(events.map { it.id.url }.distinct()).hasSize(55)
    }

    // the ticketing and access blocks are verbatim from a real listing, where they ran to 414 chars
    // against the gig's own few hundred
    @Test
    fun `scopes Scala page text to the lineup and the About section`() {
        val html = """
            <nav><a>Home</a></nav>
            <div id="post-1" class="post-1 event type-event event-post">
                <h1 class="entry-title">Doom Night</h1>
                <div class="entry-content">
                    <div class="tb-event-headerbox">
                        <div class="tb-event-headerbox-titlebox">
                            <p class="event-date">Wednesday 19th August 2026</p>
                            <p class="promoter">Doom Promotions presents </p>
                            <h1 class="event-title">Doom Night</h1>
                            <h2 class="event-subtitle">Plus Kings Of Thrash</h2>
                            <p class="event-time">7:30 pm until 10:15 pm</p>
                            <div class="left-morebox"><a href="https://link.dice.fm/x">Buy tickets</a></div>
                            <div class="right-morebox"><a href="#tickets">Info</a></div>
                        </div>
                    </div>
                    <div>Tickets Price: From £36.47 <p class="guide-to">Read our guide to buying and using tickets.</p></div>
                    <div>Admission <p class="event-time">Doors open at 7:30 PM</p><p class="age-restrictions">Age: You must be 18 years of age or more to attend this event (no exceptions). | Photo ID – We require original physical (non-digital) photo ID and use ID scanning.</p></div>
                    <h3>About Doom Night</h3>
                    <p>Doom metal night!</p>
                    <p class="add-calendar">Add to iCal | Add to Google calendar</p>
                </div>
            </div>
            <div id="sidebar"><ul><li>Other Gig</li></ul></div>
        """.trimIndent()

        val pageText = ScalaGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night!")).isTrue()
        expectThat(pageText.contains("Kings Of Thrash")).isTrue()
        expectThat(pageText.contains("Doom Promotions presents")).isTrue()
        // the gig's date is a field of its own, so as prose it only reads as a second one
        expectThat(pageText.contains("Wednesday 19th August 2026")).isEqualTo(false)
        expectThat(pageText.contains("7:30 pm until")).isEqualTo(false)
        expectThat(pageText.contains("Buy tickets")).isEqualTo(false)
        expectThat(pageText.contains("Photo ID")).isEqualTo(false)
        expectThat(pageText.contains("Read our guide")).isEqualTo(false)
        expectThat(pageText.contains("Add to iCal")).isEqualTo(false)
        expectThat(pageText.contains("Other Gig")).isEqualTo(false)
    }
}
