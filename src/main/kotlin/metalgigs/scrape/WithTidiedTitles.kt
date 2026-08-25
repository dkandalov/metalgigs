package metalgigs.scrape

import metalgigs.Gig
import metalgigs.GigTitle
import metalgigs.scrape.venues.cancelledSuffix
import kotlin.text.RegexOption.COMMENTS
import kotlin.text.RegexOption.IGNORE_CASE

// Why titles are tidied as read: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
internal class WithTidiedTitles(private val source: GigsSource) : GigsSource by source {
    override fun latestGigs(): List<Gig> = source.latestGigs().map { gig ->
        gig.copy(title = GigTitle(tidied(gig.title.value)))
    }

    private fun tidied(title: String): String {
        val tidied = title
            .replace(leadingFreeEntry, "")
            .replace(trailingFreeEntry, "")
            .replace(trailingCity, "")
            .replace(trailingSoldOut, " - SOLD OUT")
            .let(::bandsSeparated)
            .trim()
        return tidied.ifBlank { title }
    }

    // A title listing its bands with commas joins the last of them with the "+" rather than
    // separating two acts - "INHUMAN NATURE, PUPPY, AGNOSY + MORE".
    private fun bandsSeparated(title: String) =
        if (title.contains(',')) title else title.replace(billSeparator, " / ")

    // Signature Brew ends its titles with where the gig is, and every venue here is in London.
    private val trailingCity = Regex("""\s*\|\s*London(?=(${Regex.escape(cancelledSuffix)})?\s*$)""", IGNORE_CASE)

    // Spaced, so a "+" written against a word - part of a name rather than a bill - is left alone.
    private val billSeparator = Regex("""\s+\+\s+""")

    // Three venues write it three ways - "- SOLD OUT", "| SOLD OUT", "– SOLD OUT!".
    private val trailingSoldOut = Regex(
        """
        \s* [-–—:|/]+ \s*
        [(\[]? \s* sold \s* out \s* [!.]* \s* [)\]]?
        \s* $
        """,
        setOf(IGNORE_CASE, COMMENTS),
    )

    // The punctuation is what identifies it: "Free Entry Fridays" is a gig's name.
    private val leadingFreeEntry = Regex(
        """
        ^ \s*
        (?:
            free \s* entry \s* [-–—:|/]+       # FREE ENTRY: the gig
          | [(\[] \s* free \s* entry [)\]]     # [FREE ENTRY] the gig
        )
        \s*
        """,
        setOf(IGNORE_CASE, COMMENTS),
    )

    private val trailingFreeEntry = Regex(
        """
        \s*
        (?:
            [-–—:|/]+ \s* free \s* entry       # the gig - FREE ENTRY, the gig // Free Entry
          | [(\[] \s* free \s* entry [)\]]     # the gig [FREE ENTRY]
        )
        \s* $
        """,
        setOf(IGNORE_CASE, COMMENTS),
    )
}
