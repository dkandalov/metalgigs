package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Status.Companion.NOT_FOUND
import org.jsoup.parser.Parser

// shared by every venue listed through Dice.
// Why the partner API and its public key: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
private class DicePartnerVenueGigsSource(
    // A gig's url is the one dice.fm redirects to rather than the one this API lists, so this has to
    // be a client that hands a redirect back rather than following it - see gigUrlFor.
    private val client: HttpHandler,
    private val venueFilter: String,
    override val venue: Venue,
    private val notAGig: (DicePartnerEvent) -> Boolean = { false },
    private val placeholderCopy: Regex? = null,
    private val venuesOwnLines: Regex? = null,
    private val skippableGigs: Set<GigUrl> = emptySet(),
) : GigsSource {
    override fun latestGigs(): List<Gig> {
        val events = DicePartnerApi(client).eventsAt(venueFilter)
        check(events.isNotEmpty()) { "Dice returned no events for $venueFilter" }

        return events
            .filterNot(notAGig)
            .mapNotNull { event ->
                val gigUrl = gigUrlFor(event)
                gigOrSkipped(gigUrl, skippableGigs) {
                    Gig(
                        GigId(venue.id, gigUrl),
                        titleWithCancellation(event),
                        event.startsOn,
                        posterUrlFrom(gigUrl, event.images.firstOrNull()),
                        descriptionOf(event),
                        lineupFrom(event.artists),
                        statusOf(event),
                    )
                }
            }
    }

    private fun titleWithCancellation(event: DicePartnerEvent): GigTitle =
        titleFrom(if (statusOf(event) == GigStatus.Cancelled) "${event.name}$cancelledSuffix" else event.name)

    private fun statusOf(event: DicePartnerEvent): GigStatus? = if (event.status == "cancelled") GigStatus.Cancelled else null

    // A promoter's copy can arrive pasted from markup with its entities still in it: "UK &amp; Europe"
    // on one of Strongroom's 18 gigs on 2026-11-25, the same in the api's `description`.
    private fun descriptionOf(event: DicePartnerEvent): GigDescription {
        val copy = Parser.unescapeEntities(event.rawDescription, false)
        return GigDescription(
            when {
                placeholderCopy?.matches(copy.trim()) == true -> ""
                venuesOwnLines != null -> copy.lines().filterNot { venuesOwnLines.matches(it.trim()) }.joinToString("\n").replace(blankRun, "\n\n").trim()
                else -> copy
            }
        )
    }

    private val blankRun = Regex("""\n{3,}""")

    // Why the redirect target is the gig's url: docs/adr/0005-a-gig-is-identified-by-the-url-it-lives-at.md
    private fun gigUrlFor(event: DicePartnerEvent): GigUrl {
        val listed = eventUrl + event.permName
        val response = get(listed)
        return when {
            response.status.redirection -> {
                val location = checkNotNull(response.header("location")) { "dice.fm redirected $listed without saying where to" }
                check(location.startsWith(eventUrl)) { "dice.fm redirected $listed to $location, which isn't one of its event pages" }
                // Two events can share a perm_name, and dice.fm redirects it to one of them: Blondies
                // Brewery listed El Altar Del Holocausto twice on 2026-11-25, as g7o5aa and yy26mr.
                if (location.startsWith("$eventUrl${event.hash}-")) GigUrl(location)
                else urlUnderItsHash(event)
            }
            // a perm_name dice.fm serves as it stands, unprefixed
            response.status.successful -> GigUrl(listed)
            // dice.fm sometimes answers a bare perm_name with a 404 where it should be a 308: one of
            // Strongroom's 23 events on 2026-11-19 and five of them minutes later, all on sale and on
            // the venue's own widget. The page was still served where those redirects point, under
            // the event's hash - so that url is asked for, and taken only if dice.fm serves it.
            response.status == NOT_FOUND -> urlUnderItsHash(event)
            else -> error("Failed to resolve $listed: ${response.status}")
        }
    }

    private fun urlUnderItsHash(event: DicePartnerEvent): GigUrl {
        val underItsHash = "$eventUrl${event.hash}-${event.permName}"
        val status = get(underItsHash).status
        check(status.successful) { "dice.fm serves no page of this event's at $eventUrl${event.permName}, and answers $underItsHash with $status" }
        return GigUrl(underItsHash)
    }

    private fun get(url: String) = client(Request(GET, url).header("User-Agent", browserUserAgent))

    private val eventUrl = "https://dice.fm/event/"

    // dice.fm answers a request without a browser-like User-Agent with a 403 rather than the redirect
    private val browserUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}

// The one spelling of each, whether a source writes it from a listing's own status field or
// WithTidiedTitles normalises one a venue typed its own way (ADR 6).
internal const val cancelledSuffix = " - CANCELLED"
internal const val soldOutSuffix = " - SOLD OUT"

val twoTwoNine = Venue(VenueId("229"), "229")

class TwoTwoNineGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "229", venue = twoTwoNine)

val signatureBrewHaggerston = Venue(VenueId("signature-brew-haggerston"), "Signature Brew Haggerston")

class SignatureBrewHaggerstonGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "Signature Brew Haggerston", venue = signatureBrewHaggerston, notAGig = comedyIncorporated)

// Comedy Incorporated runs a stand-up night rather than a gig, seven of the 45 events this venue
// listed on 2026-08-29 - five under its name alone and two subtitled "Killer 10s".
private val comedyIncorporated = namedLike(Regex("""Comedy Incorporated""", RegexOption.IGNORE_CASE))

val signatureBrewBlackhorseRoad = Venue(VenueId("signature-brew-blackhorse-road"), "Signature Brew Blackhorse Road")

class SignatureBrewBlackhorseRoadGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "Signature Brew Blackhorse Road", venue = signatureBrewBlackhorseRoad)

val blondiesBreweryTaproom = Venue(VenueId("blondies-taproom"), "Blondies Brewery Taproom")

class BlondiesBreweryTaproomGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "Blondies Brewery", venue = blondiesBreweryTaproom)

val blondiesBar = Venue(VenueId("blondies-bar"), "Blondies Bar")

class BlondiesBarGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "Blondies", venue = blondiesBar)

val helgis = Venue(VenueId("helgis"), "Helgi's")

class HelgisGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "Helgi's", venue = helgis)

val barfly = Venue(VenueId("barfly"), "Barfly")

class BarflyGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(
        client,
        venueFilter = "Barfly Camden",
        venue = barfly,
        skippableGigs = setOf(
            // published with no image: `images` is empty and every size under `event_images` null, the
            // only one of the 27 events listed on 2026-11-25
            GigUrl("https://dice.fm/event/5wo55w-mock-tudors-31st-jan-barfly-camden-london-tickets"),
        ),
    )

val strongroom = Venue(VenueId("strongroom"), "Strongroom")

class StrongroomGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "Strongroom", venue = strongroom, notAGig = strongroomsOtherNights)

// The bar's standing nights that aren't gigs, four of the 23 events it listed on 2026-11-19: Kino's
// film screening twice, the monthly quiz and Fireworks Comedy's stand-up.
private val strongroomsOtherNights = namedLike(Regex("""Short Film Open Screen|Quiz Night|Fireworks Comedy""", RegexOption.IGNORE_CASE))

val shacklewellArms = Venue(VenueId("shacklewell-arms"), "The Shacklewell Arms")

// DJ nights are more than half of what it lists, 51 of the 96 events on 2026-11-26 under fifteen
// names, so they are dropped by how Dice files them rather than by name.
class ShacklewellArmsGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(
        client,
        venueFilter = "The Shacklewell Arms",
        venue = shacklewellArms,
        notAGig = { event -> !event.isFiledAsAGig },
        placeholderCopy = supportsUnannounced,
    )

// What the venue writes where a promoter wrote nothing, the whole description of ten of its 45
// gigs on 2026-11-26 - nine as "Support act(s): tbc" and one as "Support acts(s): tbc".
private val supportsUnannounced = Regex("""Support acts?\(s\): tbc""", RegexOption.IGNORE_CASE)

val earth = Venue(VenueId("earth"), "EartH")

// Dice's filter also answers with a night shared between venues - Pitchfork's Dalston Takeover, at
// "Various Venues: EartH, Shacklewell Arms, ..." - and 13 of the 65 events listed on 2026-11-26 were
// DJ nights, comedy, talks and a record fair.
class EarthGigsSource(client: HttpHandler) :
    GigsSource by DicePartnerVenueGigsSource(
        client,
        venueFilter = "EartH",
        venue = earth,
        notAGig = { event -> !event.isFiledAsAGig || event.venue != "EartH" },
        venuesOwnLines = earthsOwnLines,
    )

// The venue opens each listing on its room and closes it on its access FAQ, its studios and
// sometimes a levy, each a line of its own around the promoter's copy.
// Why they are cut: docs/adr/0007-a-description-is-the-gigs-own-copy.md
private val earthsOwnLines = Regex(
    """This event will take place in EartH \w+\.|For more information on access at EartH.*|EartH is a social enterprise.*|Ticket price includes a £[\d.]+ venue restoration levy\.?""",
    RegexOption.IGNORE_CASE,
)

private fun namedLike(pattern: Regex): (DicePartnerEvent) -> Boolean = { event -> pattern.containsMatchIn(event.name) }
