import dev.forkhandles.result4k.onFailure
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.ChatRequest
import org.http4k.ai.llm.chat.ChatResponseFormat
import org.http4k.ai.llm.model.Content
import org.http4k.ai.llm.model.Message
import org.http4k.ai.llm.model.ModelParams
import org.http4k.ai.model.ModelName
import org.http4k.core.HttpHandler
import java.time.LocalDate

// a venue that only posts its gig calendar as a single poster image covering many gigs (e.g. a
// monthly flyer on social media) rather than a page we can scrape normally - not tied to any one
// platform, just "here's an image, here's something to point the gig's url at"

fun extractPosterGigs(client: HttpHandler, chat: Chat, imageUrl: String, sourceUrl: String, venue: Venue): List<Gig> {
    val image = fetchImageContent(client, imageUrl)
    val request = ChatRequest(
        Message.User(listOf(image)),
        ModelParams(posterExtractionModel, responseFormat = ChatResponseFormat.Text),
    )
    val response = chat(request).onFailure { error("Poster extraction failed for $venue at $imageUrl: $it") }
    val reply = response.message.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
    val parsed = parsePosterReply(reply)
    check(parsed.isNotEmpty()) { "Could not parse any gigs from poster extraction reply for $venue at $imageUrl: \"$reply\"" }

    // The title is the description because a poster gig has no event page behind it - the flyer is
    // all there is, and what the extraction read off it is the title. "" would say a page was read
    // and had nothing to say about the gig, which is the one thing that never happened here.
    return parsed
        .filterNot { (_, title) -> isExcluded(venue, title) }
        .map { (date, title) -> Gig(GigId(venue.id, posterGigUrl(sourceUrl, title, date)), GigTitle(title), date, PosterUrl(imageUrl), GigDescription(title)) }
}

// each gig's url is synthesized from the poster's own source url (the post/page it came from) -
// there's no per-gig page to link to, so every gig from one poster shares that same real, working
// url, disambiguated by a fragment; clicking it lands on the actual poster, just not scrolled to
// this specific gig, since that's not something the source itself supports
fun posterGigUrl(sourceUrl: String, title: String, date: LocalDate): String = "$sourceUrl#gig-${slug(title)}-$date"

val posterExtractionSystemPrompt = """
    You extract gig listings from a poster image advertising multiple gigs at one venue, often a
    whole month's calendar. Reply with one gig per line, formatted exactly as:
    yyyy-MM-dd | Title
    List every distinct gig you can identify, in the order they appear on the poster. Reply with
    that and nothing else - no headers, no bullets, no commentary, no blank lines.
""".trimIndent()

private val posterExtractionModel = ModelName.of("claude-sonnet-5")

private fun parsePosterReply(reply: String): List<Pair<LocalDate, String>> =
    reply.lines().mapNotNull { line ->
        posterGigLinePattern.matchEntire(line.trim())?.let { m -> LocalDate.parse(m.groupValues[1]) to m.groupValues[2].trim() }
    }

private val posterGigLinePattern = Regex("""(\d{4}-\d{2}-\d{2})\s*\|\s*(.+)""")

private fun isExcluded(venue: Venue, title: String): Boolean =
    excludedTitlePatternsByVenue[venue.id]?.containsMatchIn(title) == true

val theDev = Venue(VenueId("the-dev"), "The Dev")

// recurring non-gig event types a venue's own poster doesn't distinguish from actual gigs - e.g.
// The Dev runs a regular karaoke night on the same monthly flyer as its band shows, with nothing
// about the listing itself (format, image) marking it apart from a real gig except its title
private val excludedTitlePatternsByVenue: Map<VenueId, Regex> = mapOf(
    theDev.id to Regex("karaoke", RegexOption.IGNORE_CASE),
)
