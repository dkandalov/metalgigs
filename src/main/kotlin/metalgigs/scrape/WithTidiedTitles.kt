package metalgigs.scrape

import metalgigs.Gig
import metalgigs.GigTitle
import metalgigs.scrape.venues.cancelledSuffix
import kotlin.text.RegexOption.COMMENTS
import kotlin.text.RegexOption.IGNORE_CASE

// A gig's title as this page writes it rather than as its venue does, decided as the listing is read
// so that the classifier's prompt and the words a moved gig is paired by see the same title.
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
        // a listing whose whole title is one of these has said nothing else about the gig, and the
        // blank would reach GigTitle as a source that has stopped parsing
        return tidied.ifBlank { title }
    }

    // A title listing its bands with commas is joining the last of them with the "+" rather than
    // separating two acts - "INHUMAN NATURE, PUPPY, AGNOSY + MORE", where a slash reads as a name.
    private fun bandsSeparated(title: String) =
        if (title.contains(',')) title else title.replace(billSeparator, " / ")

    // Signature Brew ends its titles with where the gig is, and every venue here is in London.
    private val trailingCity = Regex("""\s*\|\s*London(?=(${Regex.escape(cancelledSuffix)})?\s*$)""", IGNORE_CASE)

    // One spelling of a bill across the page. Spaced, so a "+" written against a word - part of a
    // name rather than a bill - is left alone.
    private val billSeparator = Regex("""\s+\+\s+""")

    // Three venues write it three ways - "- SOLD OUT", "| SOLD OUT", "– SOLD OUT!" - and a card
    // says it one way, the way a scrape already writes a cancellation.
    private val trailingSoldOut = Regex(
        """
        \s* [-–—:|/]+ \s*
        [(\[]? \s* sold \s* out \s* [!.]* \s* [)\]]?
        \s* $
        """,
        setOf(IGNORE_CASE, COMMENTS),
    )

    // A venue's own promotion rather than the gig's name. The punctuation is what identifies it:
    // without any, a title is saying the words rather than appending them, as "Free Entry Fridays"
    // would, and keeps them.
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
