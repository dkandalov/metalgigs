package metalgigs.scrape

import metalgigs.Gig
import metalgigs.GigTitle
import metalgigs.scrape.venues.cancelledSuffix

// Signature Brew ends its listings' titles with where the gig is, and every venue here is in London,
// so that says nothing about which gig it is. Dropped as the listing is read rather than as the page
// is written, so the classifier's prompt and a moved gig's pairing lose it too.
internal class WithoutTrailingCity(private val source: GigsSource) : GigsSource by source {
    override fun latestGigs(): List<Gig> = source.latestGigs().map { gig ->
        gig.copy(title = GigTitle(gig.title.value.replace(trailingCity, "")))
    }

    private val trailingCity = Regex("""\s*\|\s*London(?=(${Regex.escape(cancelledSuffix)})?\s*$)""", RegexOption.IGNORE_CASE)
}
