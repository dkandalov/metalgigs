import org.http4k.template.ViewModel
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class GigCardView(val title: String, val venue: String, val url: String, val imageUrl: String)

data class DateGroup(val date: String, val gigs: List<GigCardView>)

data class GigsView(val dateGroups: List<DateGroup>) : ViewModel {
    override fun template() = "gigs"
}

private val monthsByShortName = Month.entries.associateBy { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

private fun GigEvent.date(): LocalDate = LocalDate.of(year, monthsByShortName.getValue(month), day.toInt())

private fun GigEvent.toCardView() = GigCardView(
    title = title,
    venue = venue,
    url = url,
    imageUrl = "images/${localImageFileName(imageUrl)}",
)

fun groupGigsByDate(gigs: List<GigEvent>): List<DateGroup> =
    gigs.sortedBy { it.date() }
        .groupBy { it.date() }
        .map { (date, gigsOnDate) ->
            DateGroup(
                date = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)),
                gigs = gigsOnDate.map { it.toCardView() },
            )
        }
