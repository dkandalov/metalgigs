package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.time.LocalDate
import kotlin.test.Test

class WindmillBrixtonGigsSourceTest {

    @Test
    fun `extracts gig events from Windmill Brixton's listings page, following pagination`() {
        val events = assertScrapesGigs(
            source = WindmillBrixtonGigsSource(cachedClient()),
            size = 27,
            first = Gig(
                GigId(windmillBrixton.id, "https://www.windmillbrixton.co.uk/events/2026-08-14-house-arrest-george-jr-and-the-9-slash-11s-rampressure-skunkworm-the-windmill"),
                GigTitle("House Arrest, George Jr & the 9/11s, Rampressure, Skunkworm"),
                LocalDate.of(2026, 8, 14),
                PosterUrl("https://musicglue-images-prod.global.ssl.fastly.net/windmill-brixton/event/2026-08-14-house-arrest-george-jr-and-the-9-slash-11s-rampressure-skunkworm-the-windmill?u=aHR0cHM6Ly9tdXNpY2dsdWUtdXNlci1hcHAtcC01LXAuczMuYW1hem9uYXdzLmNvbS9vcmlnaW5hbHMvMzE1MDZlNzEtNTRiZC00YmQzLTk3Y2YtZmE3ZWIxNTUwYzFm&v=2"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(windmillBrixton.id, "https://www.windmillbrixton.co.uk/events/2026-11-19-grommet-the-windmill"),
                GigTitle("Grommet"),
                LocalDate.of(2026, 11, 19),
                PosterUrl("https://musicglue-images-prod.global.ssl.fastly.net/windmill-brixton/event/2026-11-19-grommet-the-windmill?u=aHR0cHM6Ly9tdXNpY2dsdWUtdXNlci1hcHAtcC00LXAuczMuYW1hem9uYXdzLmNvbS9vcmlnaW5hbHMvYmJmYjAwYzItMDk2Ny00NmM4LWJiZjYtNmEyZDBhZDU3MTY4&v=2"),
                GigDescription(""),
            ),
            urlPrefix = "https://www.windmillbrixton.co.uk/events/",
        )

        // 24 on the first page, 3 on the second - the last page still carries a "Next" link, so a
        // size assertion alone wouldn't catch following it back into the page just read
        expectThat(events.map { it.id.url }.distinct()).hasSize(27)
        // the two untitled (halo) shows are consecutive nights, and the cards only say "Mon, Sep 14"
        // and "Tue, Sep 15" - the year comes from the event path
        expectThat(events.filter { it.title == GigTitle("untitled (halo)") }.map { it.date })
            .containsExactly(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15))
    }

    // the entry, ticket and sharing furniture is verbatim from a real listing, where between them
    // they ran longer than the gig's own copy
    @Test
    fun `scopes Windmill Brixton page text to the promoter's own copy`() {
        val html = """
            <nav><a>Listings</a><a>Visitor Info</a></nav>
            <article class="Event EventDetail">
                <p class="EventDetailPromoterPresents">The Windmill presents:</p>
                <h1 class="EventDetailTitle-title">Doom Night</h1>
                <div class="EventDetailEntry">
                    <span class="EventDetailPrice-price">£5</span>
                    <p class="EventDetailEntry-requirements">Entry Requirements: 18+</p>
                </div>
                <div class="EventTickets">
                    <div class="EventTicket"><span>General Admission (e-ticket)</span>
                        <span class="Price">£5.00</span><span class="ServiceCharge">+ £1 s/c</span>
                    </div>
                </div>
                <div class="EventDetailDescription">
                    <p>Doom metal night, with <a href="https://instagram.com/x">Kings Of Thrash</a> in support.</p>
                </div>
                <div class="EventDetailSharing"><a><span>Share</span></a><a><span>Tweet</span></a></div>
            </article>
            <div class="MailingList"><p>By signing up you agree to receive news and offers from Windmill Brixton.</p></div>
        """.trimIndent()

        val pageText = WindmillBrixtonGigsSource(noHttp).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night, with Kings Of Thrash in support.")).isTrue()
        expectThat(pageText.contains("Entry Requirements")).isEqualTo(false)
        expectThat(pageText.contains("General Admission")).isEqualTo(false)
        expectThat(pageText.contains("s/c")).isEqualTo(false)
        expectThat(pageText.contains("Tweet")).isEqualTo(false)
        expectThat(pageText.contains("Visitor Info")).isEqualTo(false)
        expectThat(pageText.contains("news and offers")).isEqualTo(false)
    }
}
