// The id is what a venue is known by wherever it's stored or typed - in the log, on the command
// line - and so stays put even if the venue renames itself. The name is only ever displayed.
data class VenueId(val value: String) {
    override fun toString() = value
}

data class Venue(val id: VenueId, val name: String) {
    override fun toString() = name
}

// A gig carries only its venue's id, so every venue whose gigs are in the log has to be here for
// rendering to get a name back - including the poster-only ones, which have no GigsSource.
val allVenues: List<Venue> = listOf(
    cartAndHorses,
    newCrossInn,
    ourBlackHeart,
    theUnderworld,
    theDome,
    fiddlersElbow,
    blondiesBreweryTaproom,
    blondiesBar,
    helgis,
    electricBallroom,
    electricBrixton,
    dingwalls,
    theGarage,
    roundhouse,
    signatureBrewBlackhorseRoad,
    signatureBrewHaggerston,
    o2ForumKentishTown,
    o2AcademyBrixton,
    theGrace,
    o2AcademyIslington,
    o2ShepherdsBushEmpire,
    unionChapel,
    scala,
    twoTwoNine,
    alexandraPalace,
    paperDressVintage,
    theDev,
)

private val venuesById = allVenues.associateBy { it.id }

fun venue(id: VenueId): Venue = venuesById[id] ?: error("Unknown venue id: $id. Known venue ids: ${venuesById.keys}")
