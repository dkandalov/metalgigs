package metalgigs.scrape

import metalgigs.GigTitle
import metalgigs.Venue
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import kotlin.test.Test

// Why titles are tidied as read: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
class WithTidiedTitlesTest {

    @Test
    fun `drops the city a venue ends its titles with`() {
        expectThat(titlesListedAs(listOf("Disco 2000 Summer Yard Party | London", "DUCK & DIVE FESTIVAL 2027 | LONDON")))
            .isEqualTo(listOf(GigTitle("Disco 2000 Summer Yard Party"), GigTitle("DUCK & DIVE FESTIVAL 2027")))
        // the city a listing appends goes, the one the gig is named after stays, both off one title
        expectThat(titlesListedAs(listOf("North & East London Fiesta Weekender | London")))
            .isEqualTo(listOf(GigTitle("North & East London Fiesta Weekender")))
        // the cancellation a source notes past the city is ours, not the venue's own last word
        expectThat(titlesListedAs(listOf("Tribute To Nothing | London - CANCELLED")))
            .isEqualTo(listOf(GigTitle("Tribute To Nothing - CANCELLED")))
    }

    @Test
    fun `leaves a city the title says something with`() {
        val meantAsWritten = listOf("Anette Olzon In London", "North & East London Fiesta Weekender")
        expectThat(titlesListedAs(meantAsWritten)).isEqualTo(meantAsWritten.map(::GigTitle))
    }

    @Test
    fun `separates a bill's bands with a slash`() {
        expectThat(titlesListedAs(listOf("FOSSILIZATION + PHOBOCOSM", "The Fire Doors + The InCureables")))
            .isEqualTo(listOf(GigTitle("FOSSILIZATION / PHOBOCOSM"), GigTitle("The Fire Doors / The InCureables")))
        // the city and the bill off one title
        expectThat(titlesListedAs(listOf("LOLA (AUS) + Lucky Hit | London")))
            .isEqualTo(listOf(GigTitle("LOLA (AUS) / Lucky Hit")))
    }

    @Test
    fun `leaves the plus in a title that lists its bands with commas`() {
        val meantAsWritten = listOf("HEAVY HALLOWEEN ft. INHUMAN NATURE, PUPPY, AGNOSY + MORE")
        expectThat(titlesListedAs(meantAsWritten)).isEqualTo(meantAsWritten.map(::GigTitle))
    }

    @Test
    fun `leaves a plus written against a word`() {
        val meantAsWritten = listOf("+/-", "Rock+Roll Karaoke")
        expectThat(titlesListedAs(meantAsWritten)).isEqualTo(meantAsWritten.map(::GigTitle))
    }

    @Test
    fun `writes a sold out marker one way`() {
        expectThat(titlesListedAs(listOf("ARCH ENEMY | SOLD OUT", "Madra Salach – SOLD OUT!", "CHELSEA WOLFE - SOLD OUT")))
            .isEqualTo(listOf(GigTitle("ARCH ENEMY - SOLD OUT"), GigTitle("Madra Salach - SOLD OUT"), GigTitle("CHELSEA WOLFE - SOLD OUT")))
    }

    @Test
    fun `drops a free entry note off either end of a title`() {
        expectThat(
            titlesListedAs(
                listOf(
                    "HELGI'S 8th ANNIVERSARY PARTY - FREE ENTRY",
                    "IAN / MATADOR / CABIRIA [FREE ENTRY]",
                    // the city it carries mid-title stays, the note tied on after it goes
                    "Korn | London | After Tour Party // Free Entry",
                    "FREE ENTRY: APØLLØ “Code Name” Release party + THE SERENITY CLUB + VENETOR",
                )
            )
        ).isEqualTo(
            listOf(
                GigTitle("HELGI'S 8th ANNIVERSARY PARTY"),
                GigTitle("IAN / MATADOR / CABIRIA"),
                GigTitle("Korn | London | After Tour Party"),
                GigTitle("APØLLØ “Code Name” Release party / THE SERENITY CLUB / VENETOR"),
            )
        )
    }

    @Test
    fun `leaves a free entry note the title carries in the middle`() {
        val meantAsWritten = listOf("Open Mic - Bubblebath - FREE ENTRY - ALL WELCOME")
        expectThat(titlesListedAs(meantAsWritten)).isEqualTo(meantAsWritten.map(::GigTitle))
    }

    @Test
    fun `leaves a title that is nothing but the note`() {
        val meantAsWritten = listOf("[FREE ENTRY]", "FREE ENTRY:")
        expectThat(titlesListedAs(meantAsWritten)).isEqualTo(meantAsWritten.map(::GigTitle))
    }

    private fun titlesListedAs(titles: List<String>): List<GigTitle> {
        val listing = object : GigsSource {
            override val venue = Venue(someVenue, "Some Venue")
            override fun latestGigs() =
                titles.mapIndexed { i, title -> gig(GigTitle(title), "https://example.com/$i", realText) }
        }
        return WithTidiedTitles(listing).latestGigs().map { it.title }
    }
}
