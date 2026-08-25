package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import kotlin.test.Test

// Why a title is composed from the bill: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
class WithBilledGuestsTest {

    // the guests off the GRAVEKVLT page, which gives each act a paragraph of its own
    @Test
    fun `titles a gig with the guests billed under its headliner`() {
        val copy = contentOf("Presented by Human_Disease_Promo & The Black Heart", "GRAVEKVLT", "Plus guests…", "MORTAR", "ISHTAR TERRA")

        expectThat(billedGuests.billedTitle(copy, GigTitle("GRAVEKVLT")))
            .isEqualTo(GigTitle("GRAVEKVLT / MORTAR / ISHTAR TERRA"))
    }

    // the Handgemeng page, whose heading has dropped the umlaut its own copy gives the headliner
    @Test
    fun `takes the headliner as the copy spells it where the heading dropped its marks`() {
        val copy = contentOf("London Doom Collective presents...", "HÄNDGEMENG", "Plus guests...", "WARPSTORMER", "BIRDWITCH")

        expectThat(billedGuests.billedTitle(copy, GigTitle("Handgemeng")))
            .isEqualTo(GigTitle("HÄNDGEMENG / WARPSTORMER / BIRDWITCH"))
    }

    // Agrotóxico's page drops the marks the heading keeps, so the same rule reads the other way
    @Test
    fun `keeps the heading's spelling where the copy dropped the marks`() {
        val copy = contentOf("AGROTOXICO", "Plus special guests…", "THE RESTARTS", "FEW THOUGHTS")

        expectThat(billedGuests.billedTitle(copy, GigTitle("AGROTÓXICO")))
            .isEqualTo(GigTitle("AGROTÓXICO / THE RESTARTS / FEW THOUGHTS"))
    }

    // Greysight, where the two spell the name alike and only the copy shouts it - and every guest
    // billed here is shouted, so the heading's casing would be the one part of its own title not
    // written the way the rest of it is
    @Test
    fun `takes the copy's spelling where the two differ only in case`() {
        val copy = contentOf("GREYSIGHT", "Plus guests", "FRACTURE")

        expectThat(billedGuests.billedTitle(copy, GigTitle("Greysight")))
            .isEqualTo(GigTitle("GREYSIGHT / FRACTURE"))
    }

    // the HELSTAR page, where the bill is followed by the band's biography
    @Test
    fun `stops a bill at the copy that follows it`() {
        val copy = contentOf(
            "HELSTAR - US POWER/SPEED METAL LEGENDS RETURN TO LONDON AFTER 15 YEARS",
            "Plus guests",
            "KAINE",
            "SANHEDRIN",
            "Formed in Houston, Texas in 1982, Helstar became one of the defining names of the American power and speed metal scene.",
        )

        expectThat(billedGuests.billedTitle(copy, GigTitle("HELSTAR")))
            .isEqualTo(GigTitle("HELSTAR / KAINE / SANHEDRIN"))
    }

    // a press quote off the Cold In Berlin page and the ticket line that ends A-Sun Amissa's bill -
    // both sit where an act would, and both carry the lower case an act billed here never does
    @Test
    fun `takes nothing from a bill that is not shouted`() {
        val quotes = contentOf("COLD IN BERLIN", "Plus guests...", "'A dark whirlpool of noise' Mojo")
        val ticketLine = contentOf("Plus guests...", "£10 Adv / £15 OTD / NTAFLOF.")

        expectThat(billedGuests.billedTitle(quotes, GigTitle("COLD IN BERLIN"))).isEqualTo(null)
        expectThat(billedGuests.billedTitle(ticketLine, GigTitle("A-SUN AMISSA"))).isEqualTo(null)
    }

    // Sticky Summer Swamp II and Heavy Halloween both write "Featuring", and neither is named for
    // an act on it - where "plus guests" appends to a headliner the listing has already named
    @Test
    fun `keeps the venue's title where the bill is a programme the gig isn't named for`() {
        val copy = contentOf("Human_Disease_Promo presents", "STICKY SUMMER SWAMP II", "Featuring...", "MOLOCH", "TRIPPY WICKED", "URZAH")

        expectThat(billedGuests.billedTitle(copy, GigTitle("STICKY SUMMER SWAMP II"))).isEqualTo(null)
    }

    // seven of the venue's listings say the supports are coming without saying who
    @Test
    fun `keeps the venue's title where the bill isn't booked yet`() {
        val guestsTba = contentOf("STORMO + BELIEVE IN NOTHING", "Plus guests TBA")
        val supportTba = contentOf("FOSSILIZATION (br) and PHOBOCOSM (can)", "plus support TBA")

        expectThat(billedGuests.billedTitle(guestsTba, GigTitle("STORMO + BELIEVE IN NOTHING"))).isEqualTo(null)
        expectThat(billedGuests.billedTitle(supportTba, GigTitle("FOSSILIZATION / PHOBOCOSM"))).isEqualTo(null)
    }

    // the Phantom Spell page, whose copy is the bill and nothing else
    @Test
    fun `keeps the venue's title where nothing is billed under it`() {
        val copy = contentOf("PHANTOM SPELL", "&", "FREEWAYS")

        expectThat(billedGuests.billedTitle(copy, GigTitle("PHANTOM SPELL & FREEWAYS"))).isEqualTo(null)
    }

    // A-Sun Amissa's listing heading names both guests, and its copy bills them again underneath
    @Test
    fun `leaves out a guest the listing has already named`() {
        val copy = contentOf("Plus guests...", "SHE THE THRONE", "LOVER'S LEAP")

        expectThat(billedGuests.billedTitle(copy, GigTitle("A-SUN AMISSA & LAUREN MASON: WATER SCORES w/ SHE THE THRONE + LOVER'S LEAP")))
            .isEqualTo(null)
    }

    // the longest bill written under "plus guests" is two acts, so the bound is what holds a
    // mis-parse to a title's length rather than anything the venue writes
    @Test
    fun `takes the headliner and three guests from a longer bill`() {
        val copy = contentOf("Plus guests…", "ONE", "TWO", "THREE", "FOUR")

        expectThat(billedGuests.billedTitle(copy, GigTitle("HEADLINER")))
            .isEqualTo(GigTitle("HEADLINER / ONE / TWO / THREE"))
    }

    // every case here goes at the copy directly, so no listing is read and no http is made
    private val billedGuests = WithBilledGuests(
        SquarespaceEventsGigsSource(noHttp, url = "https://example.com/events", venue = theBlackHeart),
    )

    private fun contentOf(vararg lines: String) = GigDescription(lines.joinToString("\n"))
}
