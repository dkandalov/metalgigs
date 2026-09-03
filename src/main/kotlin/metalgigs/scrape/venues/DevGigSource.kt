package metalgigs.scrape.venues

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.obj
import com.ubertob.kondor.json.str
import dev.forkhandles.result4k.onFailure
import metalgigs.*
import metalgigs.scrape.*
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.ChatRequest
import org.http4k.ai.llm.chat.ChatResponseFormat
import org.http4k.ai.llm.model.Content
import org.http4k.ai.llm.model.Message
import org.http4k.ai.llm.model.ModelParams
import org.http4k.ai.llm.model.Resource
import org.http4k.ai.model.ModelName
import org.http4k.connect.model.Base64Blob
import org.http4k.connect.model.MimeType
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

val theDev = Venue(VenueId("the-dev"), "The Dev")

// Why the flyer is the listing: docs/adr/0011-the-devs-month-flyer-is-a-source-read-by-a-local-model.md
class DevGigSource(private val client: HttpHandler, private val chat: Chat) : GigsSource {
    override val venue = theDev

    override fun latestGigs(): List<Gig> {
        val posts = recentPosts()
        val flyer = posts.firstNotNullOfOrNull(::monthlyFlyerOrNull)
            ?: error("None of @$username's ${posts.size} most recent posts is captioned as a month's what's-on, so there is no flyer to read this month's gigs off")

        // which month's flyer was found is the whole listing, so a run that read the wrong one - or
        // last month's, the account not having posted this month's yet - says so before the gigs do
        println("  reading the ${flyer.month} flyer at ${flyer.postUrl}")

        // The month is the one thing about the flyer that is read off the caption rather than out of
        // the image, so it is what catches a date the model misread: the flyer prints one month's
        // dates, and a gig in another month is one it never carried.
        val (onTheFlyer, elsewhere) = gigsOn(flyer).partition { YearMonth.from(it.date.value) == flyer.month }
        elsewhere.forEach { println("Dropping ${it.title} on ${it.date} - $extractionModel read it off a flyer for ${flyer.month}") }
        return onTheFlyer
    }

    private fun gigsOn(flyer: MonthlyFlyer): List<Gig> {
        val request = ChatRequest(
            Message.User(listOf(imageContent(flyer.imageUrl))),
            ModelParams(extractionModel, responseFormat = ChatResponseFormat.Text),
        )
        val response = chat(request).onFailure { error("Reading $venue's ${flyer.month} flyer at ${flyer.imageUrl} failed: $it") }
        val reply = response.message.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
        val rows = flyerRows(reply)
        check(rows.isNotEmpty()) { "Could not parse any gigs out of $venue's ${flyer.month} flyer at ${flyer.imageUrl}: \"$reply\"" }

        // The title is the description because a flyer gig has no event page behind it - the flyer is
        // all there is, and what the extraction read off it is the title. "" would say a page was read
        // and had nothing to say about the gig, which is the one thing that never happened here.
        return rows
            .filterNot { (_, title) -> notABandNight.containsMatchIn(title) }
            .map { (date, title) -> Gig(GigId(venue.id, gigUrl(title, date)), GigTitle(title), date, PosterUrl(flyer.imageUrl), GigDescription(title)) }
    }

    // Every gig on the flyer is a night at The Dev, so a model is only ever asked to read dates and
    // names off it - little enough that a 26b model on this machine can do it, which is what makes
    // re-reading the flyer on every scrape affordable where a paid call per run would not be.
    private val extractionModel = ModelName.of("gemma4:26b")

    // The flyer prints The Dev's other nights alongside its band shows, with nothing but the title
    // telling the two apart: a recurring rock/metal karaoke, and a book launch. Not "fundraiser" -
    // August's "We Only Come Out At Night (Fundraiser)" is three bands playing.
    private val notABandNight = Regex("karaoke|book launch", RegexOption.IGNORE_CASE)

    // Why the Facebook page and a fragment: docs/adr/0005-a-gig-is-identified-by-the-url-it-lives-at.md
    private fun gigUrl(title: String, date: GigDate) = GigUrl("$gigsPageUrl#gig-${slug(title)}-$date")

    private val gigsPageUrl = "https://www.facebook.com/thedevnw1"

    // The timeline carries posts the account is only tagged in - other bands' tour posters, shot at
    // this venue and captioned like a listing - so the flyer has to be The Dev's own post, not just
    // one whose caption reads as a month's what's-on.
    private fun monthlyFlyerOrNull(post: InstagramPost): MonthlyFlyer? =
        monthOfWhatsOnCaption(post.caption)?.takeIf { post.postedBy == username }?.let { month ->
            MonthlyFlyer(month, post.imageUrl, "$postUrlPrefix${post.shortcode}/")
        }

    private fun recentPosts(): List<InstagramPost> {
        val page = Jsoup.parse(fetchPage(client, profileUrl, navigationHeaders))
        val posts = JProfilePosts.fromJson(prefetchedTimelineIn(page)).orThrow().timeline.edges.map { it.node }
        check(posts.isNotEmpty()) { "@$username's profile carries no posts at all - Instagram is answering, but not with a listing" }
        return posts
    }

    // Why the page rather than the api it fetches: docs/adr/0008-a-venue-is-read-from-the-surface-its-own-page-reads-from.md
    private fun prefetchedTimelineIn(page: Document): String {
        val script = page.select("script[type=application/json]").firstOrNull { it.data().contains(timelineField) }
            ?: error("No prefetched timeline in @$username's profile page - Instagram serves the shell alone unless the request looks like a navigation")
        return jsonObjectAfter(script.data(), userField)
    }

    // Enlarged rather than sent as served, and not converted down the way a gig's poster is: a genre
    // judgement reads artwork where this has to read a month of dates and names, the smallest of it
    // the second line of a two-line row.
    private fun imageContent(imageUrl: String): Content.Image {
        val response = client(Request(GET, imageUrl))
        check(response.status.successful) { "Failed to fetch image at $imageUrl: ${response.status}" }
        val bytes = enlarged(response.body.stream.readBytes())
        check(bytes.size <= MAX_IMAGE_BYTES) {
            "Image at $imageUrl is ${bytes.size} bytes, too large to send (limit ~$MAX_IMAGE_BYTES)"
        }
        return Content.Image(Resource.Binary(Base64Blob.encode(bytes), MimeType.IMAGE_JPG))
    }

    // written out and back because magick is a process against files, and jpeg on the way out
    // whatever went in, so what the model is sent is one format rather than the poster's own
    private fun enlarged(bytes: ByteArray): ByteArray {
        val source = File.createTempFile("flyer", null)
        val enlarged = File.createTempFile("flyer-enlarged", ".jpg")
        return try {
            source.writeBytes(bytes)
            enlargeForReading(source, enlarged)
            enlarged.readBytes()
        } finally {
            source.delete()
            enlarged.delete()
        }
    }

    private val username = "thedevcamden"

    private val profileUrl = "https://www.instagram.com/$username/"

    // Instagram embeds the timeline only for what looks like a browser opening the page: the same
    // request without the Sec-Fetch set is answered with the shell, as is a fetch() from the page
    // itself, which sends them saying cors rather than navigate.
    private val navigationHeaders = listOf(
        "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-GB,en;q=0.9",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
    )

    private val timelineField = "polaris_ordered_timeline_connection"

    private val userField = "\"xig_user_by_username\":"

    private val postUrlPrefix = "https://www.instagram.com/p/"
}

// Why the prompt names the shapes a row comes in: docs/adr/0011-the-devs-month-flyer-is-a-source-read-by-a-local-model.md
val flyerExtractionSystemPrompt = """
    You extract gig listings from a poster image advertising multiple gigs at one venue, often a
    whole month's calendar. Reply with one gig per line, formatted exactly as:
    yyyy-MM-dd | Title
    The poster's heading gives the month and year; each dated row below it gives a weekday and a
    day of the month, which with that heading make the date. Give every dated row a line of its
    own, in the order they appear, including rows whose line-up is partly unannounced ("+ support
    TBA") and recurring nights. A row you can read the date of belongs in the list even if the rest
    of it is hard to read.
    Reply with that and nothing else - no headers, no bullets, no commentary, no blank lines.
""".trimIndent()

// The month a post's caption says its picture is the what's-on for, e.g. "What's On AUGUST 2026!
// ALL EVENTS ARE FREE ENTRY!", and null for every other post of The Dev's.
internal fun monthOfWhatsOnCaption(caption: String): YearMonth? =
    whatsOnCaption.find(caption)?.let { match ->
        monthsByName[match.groupValues[1].lowercase()]?.let { YearMonth.of(match.groupValues[2].toInt(), it) }
    }

private data class MonthlyFlyer(val month: YearMonth, val imageUrl: String, val postUrl: String)

private val whatsOnCaption = Regex("""what.?s\s+on(?:\s+in)?\s+(\p{L}+)\s+(\d{4})""", RegexOption.IGNORE_CASE)

private val monthsByName: Map<String, Month> =
    Month.entries.associateBy { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() }

private fun flyerRows(reply: String): List<Pair<GigDate, String>> =
    reply.lines().mapNotNull { line ->
        flyerRowPattern.matchEntire(line.trim())?.let { m -> GigDate.parse(m.groupValues[1]) to withSpacedSlashes(m.groupValues[2].trim()) }
    }

private val flyerRowPattern = Regex("""(\d{4}-\d{2}-\d{2})\s*\|\s*(.+)""")

// Why one spelling for the slashes: docs/adr/0006-a-title-is-tidied-as-the-listing-is-read.md
private fun withSpacedSlashes(title: String) = title.replace(slashSeparator, " / ")

private val slashSeparator = Regex("""\s*/\s*""")

private fun slug(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

private const val MAX_IMAGE_BYTES = 7_000_000

// The payload sits inside Meta's own script envelope - an array of instructions the page replays -
// so the timeline is cut out of it by balancing braces from the field that holds it, rather than by
// modelling the envelope around it, which is about the page's bootstrap and not about this venue.
private fun jsonObjectAfter(text: String, field: String): String {
    val start = text.indexOf(field)
    check(start >= 0) { "No $field in the prefetched payload" }

    var depth = 0
    var inString = false
    var escaped = false
    for (i in start + field.length until text.length) {
        val char = text[i]
        when {
            escaped -> escaped = false
            inString && char == '\\' -> escaped = true
            char == '"' -> inString = !inString
            inString -> {}
            char == '{' -> depth++
            char == '}' -> if (--depth == 0) return text.substring(start + field.length, i + 1)
        }
    }
    error("$field in the prefetched payload is never closed")
}

private data class ProfilePosts(val timeline: Timeline)
private data class Timeline(val edges: List<PostEdge>)
private data class PostEdge(val node: InstagramPost)

// caption is absent on a post published without one, and Kondor fails the whole timeline over a
// field it was told to expect - the same trap Ovo Arena's ImageURL sets
private data class InstagramPost(val shortcode: String, val imageUrl: String, val author: PostAuthor, val captionOrNull: PostCaption?) {
    val caption: String get() = captionOrNull?.text.orEmpty()
    val postedBy: String get() = author.username
}

private data class PostAuthor(val username: String)
private data class PostCaption(val text: String)

private object JProfilePosts : JAny<ProfilePosts>() {
    private val polaris_ordered_timeline_connection by obj(JTimeline, ProfilePosts::timeline)

    override fun JsonNodeObject.deserializeOrThrow() = ProfilePosts(+polaris_ordered_timeline_connection)
}

private object JTimeline : JAny<Timeline>() {
    private val edges by array(JPostEdge, Timeline::edges)

    override fun JsonNodeObject.deserializeOrThrow() = Timeline(+edges)
}

private object JPostEdge : JAny<PostEdge>() {
    private val node by obj(JInstagramPost, PostEdge::node)

    override fun JsonNodeObject.deserializeOrThrow() = PostEdge(+node)
}

private object JInstagramPost : JAny<InstagramPost>() {
    private val code by str(InstagramPost::shortcode)
    private val display_uri by str(InstagramPost::imageUrl)
    private val user by obj(JPostAuthor, InstagramPost::author)
    private val caption by obj(JPostCaption, InstagramPost::captionOrNull)

    override fun JsonNodeObject.deserializeOrThrow() = InstagramPost(+code, +display_uri, +user, +caption)
}

private object JPostAuthor : JAny<PostAuthor>() {
    private val username by str(PostAuthor::username)

    override fun JsonNodeObject.deserializeOrThrow() = PostAuthor(+username)
}

private object JPostCaption : JAny<PostCaption>() {
    private val text by str(PostCaption::text)

    override fun JsonNodeObject.deserializeOrThrow() = PostCaption(+text)
}
