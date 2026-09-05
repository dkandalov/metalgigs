package metalgigs

import metalgigs.scrape.venues.*

fun venue(id: VenueId): Venue = venuesById[id] ?: error("Unknown venue id: $id. Known venue ids: ${venuesById.keys}")

// A gig carries only its venue's id, so every venue whose gigs are in the log has to be here for
// rendering to get a name back, whether or not anything still scrapes it.
val allVenues: List<Venue> = listOf(
    cartAndHorses,
    newCrossInn,
    theBlackHeart,
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
    windmillBrixton,
    islingtonAssemblyHall,
    barfly,
    indigoAtTheO2,
    theO2Arena,
    eventimApollo,
    ovoArena,
    bushHall,
)

// Top-level properties are initialised in the order the file declares them, so this can only follow
// allVenues - above it, it would silently be an empty map rather than fail.
private val venuesById = allVenues.associateBy { it.id }

// Venues whose genre isn't a judgement to make. The Dev books nothing but metal, and a gig read off
// its monthly flyer carries nothing to judge it by anyway - the flyer's row is the whole listing, so
// the gig's title is also its description, and a call would be asking a model to read back what it
// was given. Recorded as a User verdict because that is what it is, a standing decision about the
// venue rather than a judgement about the gig, which a forced reclassification then leaves alone.
val alwaysMetalVenues: Set<VenueId> = setOf(theDev.id)

// The id is what a venue is known by wherever it's stored or typed - in the log, on the command
// line, in published image file names - and is independent of the name, which is only ever
// displayed, so a venue renaming itself needn't touch it. Changing one anyway means rewriting every
// log line carrying it and renaming those images with it (see the migrate-log-format skill).
data class VenueId(val value: String) {
    init {
        require(value.isNotBlank()) { "A venue id can't be blank - nothing stored or typed under it could name a venue" }
    }
    override fun toString() = value
}

data class Venue(val id: VenueId, val name: String) {
    override fun toString() = name
}
