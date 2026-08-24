package metalgigs.scrape

import metalgigs.Gig
import metalgigs.GigTitle
import metalgigs.scrape.venues.cancelledSuffix

// A gig's title as this page writes it rather than as its venue does, decided as the listing is read
// so that the classifier's prompt and the words a moved gig is paired by see the same title.
//
// Signature Brew ends its listings' titles with where the gig is, and every venue here is in London,
// so that says nothing about which gig it is. A bill's bands are separated with "/" however the
// venue typed it, so one page doesn't spell the same thing two ways.
internal class WithTidiedTitles(private val source: GigsSource) : GigsSource by source {
    override fun latestGigs(): List<Gig> = source.latestGigs().map { gig ->
        gig.copy(title = GigTitle(tidied(gig.title.value)))
    }

    private fun tidied(title: String) = title
        .replace(trailingCity, "")
        .replace(billSeparator, " / ")

    private val trailingCity = Regex("""\s*\|\s*London(?=(${Regex.escape(cancelledSuffix)})?\s*$)""", RegexOption.IGNORE_CASE)

    // Spaced, so a "+" written against a word - part of a name rather than a bill - is left alone.
    private val billSeparator = Regex("""\s+\+\s+""")
}
