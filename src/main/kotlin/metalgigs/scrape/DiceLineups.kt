package metalgigs.scrape

import dev.forkhandles.result4k.peekFailure
import dev.forkhandles.result4k.resultFrom
import dev.forkhandles.result4k.valueOrNull
import metalgigs.Gig
import metalgigs.Lineup
import org.http4k.core.HttpHandler

// What a venue's source ends on where the venue sells through Dice, under the name Dice gives it.
// Why a venue's source also asks Dice: docs/adr/0015-a-lineup-is-the-acts-a-source-names-held-apart-from-the-title.md
internal fun List<Gig>.withLineupsOnDiceAt(venueOnDice: String, client: HttpHandler): List<Gig> =
    withLineups(DiceLineups(venueOnDice, DicePartnerApi(client)::eventsAt))

internal fun List<Gig>.withLineups(onDice: DiceLineups): List<Gig> =
    map { gig -> gig.copy(lineup = preferred(gig.lineup, onDice.lineupFor(gig))) }

// The same, for a venue whose source is one it shares with others.
internal class WithLineupsOnDiceAt(
    private val venueOnDice: String,
    private val source: GigsSource,
    private val client: HttpHandler,
) : GigsSource by source {
    override fun latestGigs(): List<Gig> = source.latestGigs().withLineupsOnDiceAt(venueOnDice, client)
}

internal fun preferred(own: Lineup, onDice: Lineup): Lineup = when {
    onDice == Lineup.none -> own
    own == Lineup.none || onDice.value.size >= 2 -> onDice
    else -> own
}

// The artists Dice lists for a gig at a venue that sells through it, whatever site the gig is read
// off.
internal class DiceLineups(private val venueOnDice: String, private val eventsAt: (String) -> List<DicePartnerEvent>) {
    fun lineupFor(gig: Gig): Lineup = eventFor(gig, events)?.let { lineupFrom(actsOn(it)) } ?: Lineup.none

    // One call answers for the venue's whole listing, so it is made when the first gig asks.
    private val events by lazy {
        resultFrom { eventsAt(venueOnDice) }
            .peekFailure { println("  Dice's lineups for $venueOnDice couldn't be read, so its gigs keep their own - ${it.message}") }
            .valueOrNull().orEmpty()
    }
}

// The event that is this gig: on its night, and under its name or billing an act the gig is named for.
// A night alone is not enough - a venue's two rooms, or an early and a late show, share one.
internal fun eventFor(gig: Gig, events: List<DicePartnerEvent>): DicePartnerEvent? {
    val listedAs = lettersOf(gig.title.value)
    return events.filter { it.startsOn == gig.date }
        .filter { event -> sameName(listedAs, lettersOf(event.name)) || event.artists.any { named(it, listedAs) } }
        .maxByOrNull { it.artists.size }
}

private fun sameName(one: String, other: String) = one == other || named(one, other) || named(other, one)

private fun named(name: String, within: String) = lettersOf(name).let { it.length >= shortestName && within.contains(it) }

// A name shorter than this is inside too many titles to say anything, unless it is the whole of one.
private const val shortestName = 4

// Dice lists a pair of artists beside each of the two - "Ben Watt", "Tracey Thorn", "Ben Watt and
// Tracey Thorn" - and the pair is the one entry that is not an act.
internal fun actsOn(event: DicePartnerEvent): List<String> =
    event.artists.filterNot { artist ->
        event.artists.count { other -> other != artist && named(other, lettersOf(artist)) } >= 2
    }
