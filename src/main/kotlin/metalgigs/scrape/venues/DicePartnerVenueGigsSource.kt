package metalgigs.scrape.venues

import metalgigs.*
import metalgigs.scrape.*
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request

// shared by every venue listed through Dice.
// Why the partner API and its public key: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
private class DicePartnerVenueGigsSource(
    // A gig's url is the one dice.fm redirects to rather than the one this API lists, so this has to
    // be a client that hands a redirect back rather than following it - see gigUrlFor.
    private val client: HttpHandler,
    private val venueFilter: String,
    override val venue: Venue,
    private val notAGig: Regex? = null,
) : GigsSource {
    override fun latestGigs(): List<Gig> {
        val events = DicePartnerApi(client).eventsAt(venueFilter)
        check(events.isNotEmpty()) { "Dice returned no events for $venueFilter" }

        return events
            .filterNot { event -> notAGig?.containsMatchIn(event.name) == true }
            .map { event ->
                val gigUrl = gigUrlFor(event.permName)
                Gig(
                    GigId(venue.id, gigUrl),
                    titleWithCancellation(event),
                    event.startsOn,
                    posterUrlFrom(gigUrl, event.images.firstOrNull()),
                    GigDescription(event.rawDescription),
                    lineupFrom(event.artists),
                )
            }
    }

    private fun titleWithCancellation(event: DicePartnerEvent): GigTitle =
        titleFrom(if (event.status == "cancelled") "${event.name}$cancelledSuffix" else event.name)

    // Why the redirect target is the gig's url: docs/adr/0005-a-gig-is-identified-by-the-url-it-lives-at.md
    private fun gigUrlFor(permName: String): GigUrl {
        val listed = eventUrl + permName
        val response = client(Request(GET, listed).header("User-Agent", browserUserAgent))
        return when {
            response.status.redirection -> {
                val location = checkNotNull(response.header("location")) { "dice.fm redirected $listed without saying where to" }
                check(location.startsWith(eventUrl)) { "dice.fm redirected $listed to $location, which isn't one of its event pages" }
                GigUrl(location)
            }
            // a perm_name dice.fm serves as it stands, unprefixed
            response.status.successful -> GigUrl(listed)
            else -> error("Failed to resolve $listed: ${response.status}")
        }
    }

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
private val comedyIncorporated = Regex("""Comedy Incorporated""", RegexOption.IGNORE_CASE)

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
    GigsSource by DicePartnerVenueGigsSource(client, venueFilter = "Barfly Camden", venue = barfly)
