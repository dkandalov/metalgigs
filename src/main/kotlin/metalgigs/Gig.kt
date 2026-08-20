package metalgigs

import java.time.LocalDate
import java.time.Month

data class Gig(
    val id: GigId,
    val title: GigTitle,
    val date: GigDate,
    val posterUrl: PosterUrl,
    val description: GigDescription,
)

data class GigId(val venueId: VenueId, val url: String) {
    init {
        require(url.isNotBlank()) { "Gig has no url, so it can't be identified: gig at $venueId" }
    }
}

data class GigTitle(val value: String) {
    init {
        require(value.isNotBlank()) { "A gig title can't be blank - a source whose title selector matched nothing has stopped parsing" }
    }
    override fun toString() = value
}

data class GigDate(val value: LocalDate) : Comparable<GigDate> {
    val year: Int get() = value.year
    val monthValue: Int get() = value.monthValue

    constructor(year: Int, month: Month, dayOfMonth: Int) : this(LocalDate.of(year, month, dayOfMonth))
    constructor(year: Int, month: Int, dayOfMonth: Int) : this(LocalDate.of(year, month, dayOfMonth))

    override fun compareTo(other: GigDate) = value.compareTo(other.value)
    override fun toString() = value.toString()

    companion object {
        fun parse(text: String) = GigDate(LocalDate.parse(text))
    }
}

data class PosterUrl(val value: String) {
    init {
        require(value.isNotBlank()) { "A poster url can't be blank - a source with no poster for a gig must find one or fail" }
    }
    override fun toString() = value
}

data class GigDescription(val value: String) {
    override fun toString() = value
}
