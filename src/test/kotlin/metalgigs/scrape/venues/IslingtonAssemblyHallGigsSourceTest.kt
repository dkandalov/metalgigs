package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import kotlin.test.Test

class IslingtonAssemblyHallGigsSourceTest {

    @Test
    fun `extracts gig events from Islington Assembly Hall's events page, following pagination`() {
        val events = assertScrapesGigs(
            source = IslingtonAssemblyHallGigsSource(cachedClient(), year = 2026),
            size = 74,
            first = Gig(
                GigId(islingtonAssemblyHall.id, "https://islingtonassemblyhall.co.uk/events/horsegirl-21st-aug-islington-assembly-hall-london-tickets/"),
                GigTitle("Horsegirl"),
                GigDate(2026, 8, 21),
                PosterUrl("https://islingtonassemblyhall.co.uk/app/uploads/2026/03/16a4e407-24c9-482a-9f0b-8f8b7812520a.jpg"),
                GigDescription(""),
            ),
            last = Gig(
                GigId(islingtonAssemblyHall.id, "https://islingtonassemblyhall.co.uk/events/seckou-keita-and-the-homeland-band-featuring-special-guests-30th-anniversary-tour-10th-feb-islington-assembly-hall-london-tickets/"),
                GigTitle("Seckou Keita and The Homeland Band ft Special Guests: 30th Anniversary Tour"),
                GigDate(2027, 11, 28),
                PosterUrl("https://islingtonassemblyhall.co.uk/app/uploads/2025/11/Untitled-design-1.jpg"),
                GigDescription(""),
            ),
            urlPrefix = "https://islingtonassemblyhall.co.uk/events/",
        )

        // eighteen to a page over five pages - the last page's "Next" is gone rather than disabled,
        // but a size assertion alone wouldn't catch a future change that looped back to page 1
        expectThat(events.map { it.id.url }.distinct()).hasSize(74)
        // the listing crosses from December into January mid-page-4, which is the only place the
        // year advances - the last gig's own page dates it 28/11/2027, and 28 Nov 2027 is the Sunday
        // its card says it is
        expectThat(events.filter { it.date.year == 2027 }).hasSize(13)
    }

    // the terms and levy paragraphs are verbatim from a real listing, where they close every one -
    // on the thinnest they were the whole description. The terms one is typed with a leading
    // asterisk on most listings and without it on others, so this has one of each.
    @Test
    fun `scopes Islington Assembly Hall page text to the copy, dropping the terms and levy paragraphs`() {
        val html = """
            <nav><a>What's On</a><a>Hire the Hall</a></nav>
            <ul class="event__details__list"><li>Date 21/08/2026</li><li>Total price, inc booking fee £27.78</li></ul>
            <div class="event__description body--wysiwyg">
                <p>Doom metal night, with Kings Of Thrash in support.</p>
                <p>By purchasing a ticket to this event you are agreeing to adhere to Islington Assembly Hall&#8217;s terms and conditions: https://islingtonassemblyhall.co.uk/customer-terms-conditions-2022/</p>
                <p>*All tickets to shows at Islington Assembly Hall are subject to a Venue Levy of £1.50 + VAT. As a Grade II listed building, this levy will be reinvested into Islington Assembly Hall and its services, meaning the customer experience can continue to be enhanced.*</p>
                <p>Presented by Doom Promotions.</p>
                <p>This is a 16+ event.</p>
            </div>
            <div class="entry__related"><li class="event__item"><a class="event__item__title">Some Other Gig</a></li></div>
        """.trimIndent()

        val pageText = IslingtonAssemblyHallGigsSource(noHttp, year = 2026).eventPageContent(pageOf(html))!!

        expectThat(pageText.contains("Doom metal night, with Kings Of Thrash in support.")).isTrue()
        // who booked the show is the one thing some of these listings say beyond the boilerplate
        expectThat(pageText.contains("Presented by Doom Promotions.")).isTrue()
        expectThat(pageText.contains("terms and conditions")).isEqualTo(false)
        expectThat(pageText.contains("Venue Levy")).isEqualTo(false)
        expectThat(pageText.contains("16+")).isEqualTo(false)
        expectThat(pageText.contains("£27.78")).isEqualTo(false)
        expectThat(pageText.contains("Some Other Gig")).isEqualTo(false)
        expectThat(pageText.contains("Hire the Hall")).isEqualTo(false)
    }
}
