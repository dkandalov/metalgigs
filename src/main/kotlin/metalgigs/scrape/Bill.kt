package metalgigs.scrape

import metalgigs.GigTitle
import metalgigs.Lineup
import metalgigs.scrape.venues.cancelledSuffix
import metalgigs.scrape.venues.soldOutSuffix

// Why the acts are held apart from the title: docs/adr/0015-a-lineup-is-the-acts-a-source-names-held-apart-from-the-title.md
internal fun lineupFrom(acts: List<String>): Lineup = Lineup(acts.map(::singlySpaced).filter { it.isNotEmpty() }.distinct())

// The acts a line under a gig's heading bills, where a strapline goes on other pages.
internal fun lineupBilledBy(strapline: String, joinedActs: JoinedActs): Lineup =
    lineupFrom(actsBilledBy(strapline).orEmpty().flatMap { actsIn(it, joinedActs) })

// A bill a promoter wrote as a line of its own in a gig's copy. Prose sits beside it there, so the
// line has to say what it is - guests or support - where a line under a heading need only open "plus".
internal fun lineupBilledInCopy(lines: List<String>, joinedActs: JoinedActs): Lineup =
    lines.map { it.trim() }.firstOrNull { it.length <= longestBillInCopy && it.matches(billInCopy) }
        ?.let { lineupBilledBy(it, joinedActs) } ?: Lineup.none

private val billInCopy = Regex(
    """(?:(?:plus|with|w/|\+)\s*)?(?:very\s+)?(?:special\s+guests?|supports?(?:\s+from)?)\b\s*[:,-]?\s*\S.*""",
    RegexOption.IGNORE_CASE,
)
private const val longestBillInCopy = 120

// A heading that is itself the bill: acts joined by "+" or "/", and by commas where a venue lists them
// so. It is taken whole or not at all - a piece that is more than a name leaves the gig with no lineup.
internal fun lineupHeadedBy(heading: GigTitle, joinedActs: JoinedActs, commaLists: Boolean = false): Lineup {
    val bill = tidied(heading.value).removeSuffix(soldOutSuffix).removeSuffix(cancelledSuffix)
    val pieces = bill.split(if (commaLists) actSeparator else Regex(" / ")).map { it.trim(::decorates).replace(trailingNote, "").trim() }
    if (pieces.size < 2 || pieces.any { it.contains(moreThanAName) }) return Lineup.none
    val acts = pieces.map { it.replace(guestLabel, "") }.filterNot { it.matches(unnamedAct) }
    return lineupFrom(acts.flatMap { actsIn(it, joinedActs) })
}

// Cart & Horses frames a heading in fire emoji, which are neither end of a name.
private fun decorates(char: Char) = char.isWhitespace() || char.isSurrogate() || char.category == CharCategory.OTHER_SYMBOL || char == '\uFE0F'

private val trailingNote = Regex("""\s*(?:[(\[][^)\]]*[)\]]|[-–—])\s*$""")
private val moreThanAName = Regex(""":|\s[-–—|]\s|\b(?:tour|presents?|showcase|launch|residency|vol\.?)\b|#\d""", RegexOption.IGNORE_CASE)
private val guestLabel = Regex("""^(?:(?:very\s+)?special\s+guests?|guests?|support(?:\s+from)?)\s*:?\s+""", RegexOption.IGNORE_CASE)
private val unnamedAct = Regex("""(?:more(?:\s+tb[ac])?|tb[ac]|(?:very\s+)?(?:special\s+)?guests?|supports?|friends|djs?|dj['’]s)""", RegexOption.IGNORE_CASE)

// Two acts a bill joins with "&" or "and", told from one act whose own name holds the word.
fun interface JoinedActs {
    fun apart(joined: String): List<String>

    companion object {
        val keptWhole = JoinedActs { listOf(it) }
    }
}

internal val joiner = Regex("""\s(?:&|and)\s""", RegexOption.IGNORE_CASE)

// "ROME and OF THE WAND AND THE MOON" is two acts, cut on the "and" that isn't shouted.
// Why the case of an "and" is trusted: docs/adr/0015-a-lineup-is-the-acts-a-source-names-held-apart-from-the-title.md
private fun actsIn(piece: String, joinedActs: JoinedActs): List<String> {
    val shouted = piece.split(lowerCaseAnd).takeIf { names -> names.size > 1 && names.all(::isShouted) }
    return when {
        shouted != null -> shouted.flatMap { if (it.contains(ampersand)) joinedActs.apart(it) else listOf(it) }
        piece.contains(joiner) -> joinedActs.apart(piece)
        else -> listOf(piece)
    }
}

private fun isShouted(name: String) = name.any(Char::isLetter) && name.none(Char::isLowerCase)

private val lowerCaseAnd = Regex("""\s+and\s+""")
private val ampersand = Regex("""\s&\s""")

// Why a title is composed from the bill: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
internal fun titleBilledBy(strapline: String, listedAs: GigTitle): GigTitle? {
    val acts = actsBilledBy(strapline) ?: return null
    return titleFrom((listOf(listedAs.value) + acts).take(actsInBilledTitle).joinToString(" / "))
}

private fun actsBilledBy(strapline: String): List<String>? {
    val bill = billMarker.matchEntire(strapline)?.groupValues?.get(1) ?: return null
    if (unbookedBill.containsMatchIn(bill)) return null
    return bill.split(actSeparator).map { it.removePrefix("& ") }
}

private val billMarker = Regex(
    """(?:.*\bspecial\s+guests?\b(?:\s+djs?\b)?\s*[:-]?\s*(?:plus\s+)?|(?:(?:with|plus|w/)\s*)?supports?(?:\s+(?:from|with))?\s*[,:]?\s+|(?:.*\btour\b.*?\s)?(?:with|plus)\s+|opener\s*:\s*|\+\s*)(.+)""",
    RegexOption.IGNORE_CASE,
)
private val unbookedBill = Regex("""\b(?:tba|tbc|to be announced|guests?|supports?)\b""", RegexOption.IGNORE_CASE)
private val actSeparator = Regex("""\s+(?:\+|/|\|)\s+|,\s+""")
private const val actsInBilledTitle = 4

// A lineup given as a field rather than written into copy. It is taken only when one of its acts is
// what the gig is listed as, the rest being appended in the order given.
// Why a title is composed from the bill: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
internal fun titleWithLineup(listedAs: GigTitle, lineup: List<String>): GigTitle? {
    val named = lettersOf(listedAs.value)
    val (inTitle, unnamed) = lineup.filter { lettersOf(it).isNotEmpty() }.partition { named.contains(lettersOf(it)) }
    if (inTitle.isEmpty() || unnamed.isEmpty()) return null
    return titleFrom((listOf(listedAs.value) + unnamed).take(actsInBilledTitle).joinToString(" / "))
}

internal fun lettersOf(text: String) = text.lowercase().filter(Char::isLetterOrDigit)
