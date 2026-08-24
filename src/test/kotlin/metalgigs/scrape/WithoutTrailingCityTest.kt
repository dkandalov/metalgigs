package metalgigs.scrape

import metalgigs.GigTitle
import metalgigs.Venue
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import kotlin.test.Test

class WithoutTrailingCityTest {

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
        val meantAsWritten = listOf("Korn | London | After Tour Party // Free Entry", "Anette Olzon In London")
        expectThat(titlesListedAs(meantAsWritten)).isEqualTo(meantAsWritten.map(::GigTitle))
    }

    private fun titlesListedAs(titles: List<String>): List<GigTitle> {
        val listing = object : GigsSource {
            override val venue = Venue(someVenue, "Some Venue")
            override fun latestGigs() =
                titles.mapIndexed { i, title -> gig(GigTitle(title), "https://example.com/$i", realText) }
        }
        return WithoutTrailingCity(listing).latestGigs().map { it.title }
    }
}
