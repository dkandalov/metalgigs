package metalgigs.render

import metalgigs.Gig
import metalgigs.GigDate
import metalgigs.publishedImageFileName
import metalgigs.venue
import org.http4k.template.ViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class GigsView(val dateGroups: List<DateGroup>) : ViewModel {
    override fun template() = "gigs"
}

data class DateGroup(val date: GigDate, val gigs: List<GigCardView>) {
    val displayDate: String = date.value.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
}

data class GigCardView(val title: String, val venue: String, val url: String, val imageUrl: String) {
    // The <wbr> the template puts between these is the only place such a title can break.
    val titleParts: List<String> = title.split(afterARunTogetherSlash)
}

// A slash with a space beside it needs no help - the space is already a break opportunity, and one
// added next to it falls in the same place.
private val afterARunTogetherSlash = Regex("""(?<=\S/)(?=\S)""")

// the page is a what's-on list rather than a calendar, and a gig a year and a half out is noise on
// it. Those gigs stay in the log and keep their published image, so one appears here of its own
// accord once it comes into range, without being rescraped or refetched.
fun gigsOnThePage(gigs: List<Gig>, today: LocalDate): List<Gig> =
    excludeGigsInThePast(gigs, today).filter { it.date <= GigDate(today.plusYears(1)) }

fun excludeGigsInThePast(gigs: List<Gig>, today: LocalDate): List<Gig> =
    gigs.filter { it.date >= GigDate(today) }

fun groupGigsByDate(gigs: List<Gig>): List<DateGroup> =
    gigs.sortedBy { it.date }
        .groupBy { it.date }
        .map { (date, gigsOnDate) ->
            DateGroup(
                date,
                // within a day the scrape order is just whichever venue happened to be scraped
                // first, which shuffles between runs - alphabetical keeps the page stable and
                // makes a given gig findable
                gigsOnDate.sortedBy { it.title.value.lowercase() }.map { it.toCardView() },
            )
        }

private fun Gig.toCardView() = GigCardView(
    title = title.value,
    venue = venue(id.venueId).name,
    url = id.url.value,
    imageUrl = "images/${publishedImageFileName(this)}",
)
