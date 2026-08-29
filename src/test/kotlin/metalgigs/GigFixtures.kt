package metalgigs

internal val someVenue = VenueId("Some Venue")

// The day a test reads its gigs on. Every fixture gig falls within a fortnight of it, so a check
// about how far ahead a listing reaches has nothing to say about tests that are about something else.
internal val someDay = GigDate(2026, 8, 8)

internal val realText = "Doom night with support from three bands, doors 7pm."

internal fun gig(title: GigTitle, url: String, description: String, poster: PosterUrl = PosterUrl("https://example.com/poster.jpg"), date: GigDate = someDay) =
    Gig(GigId(someVenue, GigUrl(url)), title = title, date, poster, GigDescription(description))
