package metalgigs.scrape

import metalgigs.Gig
import metalgigs.GigReplaced

// Why a listed gig keeps what the log holds: docs/adr/0014-a-venue-that-changes-platform-keeps-its-artwork.md
internal fun keepingDhpPosters(scraped: List<Gig>, logged: List<Gig>, replacements: List<GigReplaced>): List<Gig> {
    val loggedById = logged.associateBy { it.id }
    val replacedBy = replacements.associate { it.by to it.replaced }

    return scraped.map { gig ->
        val earlier = loggedById[gig.id] ?: replacedBy[gig.id]?.let(loggedById::get)
        if (earlier != null && dhpHosts.any { earlier.posterUrl.value.startsWith(it) })
            gig.copy(posterUrl = earlier.posterUrl, description = earlier.description)
        else gig
    }
}

private val dhpHosts = listOf("https://www.thegarage.london/", "https://www.thegrace.london/")
