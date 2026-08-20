import java.time.LocalDate

data class Gig(
    val id: GigId,
    val title: GigTitle,
    val date: LocalDate,
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

data class PosterUrl(val value: String) {
    init {
        require(value.isNotBlank()) { "A poster url can't be blank - a source with no poster for a gig must find one or fail" }
    }
    override fun toString() = value
}

data class GigDescription(val value: String) {
    override fun toString() = value
}
