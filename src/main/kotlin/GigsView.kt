import org.http4k.template.ViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class GigCardView(val title: String, val venue: String, val url: String, val imageUrl: String?)

data class DateGroup(val date: LocalDate, val gigs: List<GigCardView>) {
    val displayDate: String = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
}

data class GigsView(val dateGroups: List<DateGroup>) : ViewModel {
    override fun template() = "gigs"
}

private fun Gig.toCardView(publishedImageNames: Set<String>) = GigCardView(
    title = title.value,
    venue = venue(id.venueId).name,
    url = id.url,
    imageUrl = publishedImageFileName(this).takeIf { it in publishedImageNames }?.let { "images/$it" },
)

fun excludeGigsInThePast(gigs: List<Gig>, today: LocalDate): List<Gig> =
    gigs.filter { it.date >= today }

// the page is a what's-on list rather than a calendar, and a gig a year and a half out is noise on
// it. Those gigs stay in the log and keep their published image, so one appears here of its own
// accord once it comes into range, without being rescraped or refetched.
fun gigsOnThePage(gigs: List<Gig>, today: LocalDate): List<Gig> =
    excludeGigsInThePast(gigs, today).filter { it.date <= today.plusYears(1) }

fun groupGigsByDate(gigs: List<Gig>, publishedImageNames: Set<String>): List<DateGroup> =
    gigs.sortedBy { it.date }
        .groupBy { it.date }
        .map { (date, gigsOnDate) ->
            DateGroup(
                date = date,
                // within a day the scrape order is just whichever venue happened to be scraped
                // first, which shuffles between runs - alphabetical keeps the page stable and
                // makes a given gig findable
                gigs = gigsOnDate.sortedBy { it.title.value.lowercase() }.map { it.toCardView(publishedImageNames) },
            )
        }
