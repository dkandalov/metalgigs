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
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

val theDev = Venue(VenueId("the-dev"), "The Dev")

// The Dev prints nothing per gig: a month's bookings are one flyer, posted to Instagram, and that
// flyer is the whole listing. So this source is the flyer - found among the account's recent posts
// by its caption, and read by a model running on this machine, which costs nothing and can be asked
// again as often as it takes.
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
            .filterNot { (_, title) -> karaokeNight.containsMatchIn(title) }
            .map { (date, title) -> Gig(GigId(venue.id, gigUrl(title, date)), GigTitle(title), date, PosterUrl(flyer.imageUrl), GigDescription(title)) }
    }

    // Every gig on the flyer is a night at The Dev, so a model is only ever asked to read dates and
    // names off it - little enough that a 26b model on this machine can do it, which is what makes
    // re-reading the flyer on every scrape affordable where a paid call per run would not be.
    private val extractionModel = ModelName.of("gemma4:26b")

    // The Dev runs a regular karaoke night, printed on the monthly flyer alongside its band shows
    // with nothing but the title marking it apart from one.
    private val karaokeNight = Regex("karaoke", RegexOption.IGNORE_CASE)

    // There's no per-gig page to link to, so every gig off every flyer shares this one real, working
    // url, disambiguated by a fragment. It is the venue's Facebook page rather than the Instagram
    // post the flyer was read off, because the post is superseded every month where the page is not
    // - so a gig keeps the url it was first logged under instead of being relisted when the next
    // flyer goes up. Either way, clicking it lands on the page and not on this one gig, which
    // neither the post nor the page supports.
    private fun gigUrl(title: String, date: GigDate) = GigUrl("$gigsPageUrl#gig-${slug(title)}-$date")

    private val gigsPageUrl = "https://www.facebook.com/thedevnw1"

    private fun monthlyFlyerOrNull(post: InstagramPost): MonthlyFlyer? =
        monthOfWhatsOnCaption(post.caption)?.let { month ->
            MonthlyFlyer(month, post.imageUrl, "$postUrlPrefix${post.shortcode}/")
        }

    private fun recentPosts(): List<InstagramPost> {
        val profile = JInstagramProfile.fromJson(fetchPage(client, profileUrl, headers)).orThrow()
        val posts = profile.data.user.timeline.edges.map { it.node }
        check(posts.isNotEmpty()) { "@$username's profile carries no posts at all - Instagram is answering, but not with a listing" }
        return posts
    }

    // The flyer is sent at the size Instagram serves it, where a gig's poster is published at 768px:
    // a genre judgement reads artwork, and this has to read a month of dates and names off it.
    private fun imageContent(imageUrl: String): Content.Image {
        val response = client(Request(GET, imageUrl))
        check(response.status.successful) { "Failed to fetch image at $imageUrl: ${response.status}" }
        val mimeType = response.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
            ?.let { MimeType.of(it) } ?: mimeTypeForImageUrl(imageUrl)
        val bytes = response.body.stream.readBytes()
        check(bytes.size <= MAX_IMAGE_BYTES) {
            "Image at $imageUrl is ${bytes.size} bytes, too large to send (limit ~$MAX_IMAGE_BYTES)"
        }
        return Content.Image(Resource.Binary(Base64Blob.encode(bytes), mimeType))
    }

    private val username = "thedevcamden"

    // The profile page itself is a script that fetches this, so there is no markup to parse: what a
    // browser is served is an app shell with neither the posts nor their images anywhere in it. The
    // app id is the web client's own, sent by every logged-out browser that loads the page, and
    // without it the endpoint answers a login wall rather than the profile.
    private val profileUrl = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username"
    private val headers = listOf(
        "X-IG-App-ID" to "936619743392459",
        "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    )

    private val postUrlPrefix = "https://www.instagram.com/p/"
}

// Asking for "every distinct gig you can identify" was enough for Sonnet and not for gemma4:26b,
// which left out the one row on The Dev's August flyer whose line-up is half unannounced ("Mur
// (ISL) + Support TBA") - a row it could read the date of and didn't count as a gig. Naming the
// shapes a row comes in is what recovered it, over ten runs of five prompt variants; asking the
// model to count the rows first, which it cannot check, changed nothing either way.
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
// ALL EVENTS ARE FREE ENTRY!", and null for every other post of The Dev's. It has to be the caption
// rather than the picture: a band's own tour poster is the same typography over the same list of
// dates, and reads as a month's what's-on until you notice the dates are at other venues.
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

// A model reading a bill off a picture is inconsistent about the space around its slashes -
// "Liquified/Lobotomica" on one run, "Liquified/ Lobotomica" on the next - and a title that flips
// between runs is a gig logged as having changed, every run, for as long as it is listed. One
// spelling settles it, and " / " is the one a bill is already written with once WithTidiedTitles
// has separated it. A slash inside a name rather than between two acts is spaced along with them.
private fun withSpacedSlashes(title: String) = title.replace(slashSeparator, " / ")

private val slashSeparator = Regex("""\s*/\s*""")

private fun slug(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

private fun mimeTypeForImageUrl(url: String) =
    when (imageUrlExtension(url).lowercase()) {
        "png" -> MimeType.IMAGE_PNG
        "gif" -> MimeType.IMAGE_GIF
        "webp" -> MimeType.IMAGE_WEBP
        else -> MimeType.IMAGE_JPG
    }

private const val MAX_IMAGE_BYTES = 7_000_000

// Instagram wraps every list it returns as edges around nodes, twice over here - once for the posts
// and once for each post's caption, of which a post has either one or none.
private data class InstagramPost(val shortcode: String, val imageUrl: String, val captions: InstagramCaptions) {
    val caption: String get() = captions.edges.firstOrNull()?.node?.text.orEmpty()
}

private object JInstagramPost : JAny<InstagramPost>() {
    private val shortcode by str(InstagramPost::shortcode)
    private val display_url by str(InstagramPost::imageUrl)
    private val edge_media_to_caption by obj(JInstagramCaptions, InstagramPost::captions)

    override fun JsonNodeObject.deserializeOrThrow() = InstagramPost(+shortcode, +display_url, +edge_media_to_caption)
}

private data class InstagramCaptions(val edges: List<InstagramCaptionEdge>)
private data class InstagramCaptionEdge(val node: InstagramCaption)
private data class InstagramCaption(val text: String)

private object JInstagramCaptions : JAny<InstagramCaptions>() {
    private val edges by array(JInstagramCaptionEdge, InstagramCaptions::edges)

    override fun JsonNodeObject.deserializeOrThrow() = InstagramCaptions(+edges)
}

private object JInstagramCaptionEdge : JAny<InstagramCaptionEdge>() {
    private val node by obj(JInstagramCaption, InstagramCaptionEdge::node)

    override fun JsonNodeObject.deserializeOrThrow() = InstagramCaptionEdge(+node)
}

private object JInstagramCaption : JAny<InstagramCaption>() {
    private val text by str(InstagramCaption::text)

    override fun JsonNodeObject.deserializeOrThrow() = InstagramCaption(+text)
}

private data class InstagramProfile(val data: InstagramData)
private data class InstagramData(val user: InstagramUser)
private data class InstagramUser(val timeline: InstagramTimeline)
private data class InstagramTimeline(val edges: List<InstagramPostEdge>)
private data class InstagramPostEdge(val node: InstagramPost)

private object JInstagramProfile : JAny<InstagramProfile>() {
    private val data by obj(JInstagramData, InstagramProfile::data)

    override fun JsonNodeObject.deserializeOrThrow() = InstagramProfile(+data)
}

private object JInstagramData : JAny<InstagramData>() {
    private val user by obj(JInstagramUser, InstagramData::user)

    override fun JsonNodeObject.deserializeOrThrow() = InstagramData(+user)
}

private object JInstagramUser : JAny<InstagramUser>() {
    private val edge_owner_to_timeline_media by obj(JInstagramTimeline, InstagramUser::timeline)

    override fun JsonNodeObject.deserializeOrThrow() = InstagramUser(+edge_owner_to_timeline_media)
}

private object JInstagramTimeline : JAny<InstagramTimeline>() {
    private val edges by array(JInstagramPostEdge, InstagramTimeline::edges)

    override fun JsonNodeObject.deserializeOrThrow() = InstagramTimeline(+edges)
}

private object JInstagramPostEdge : JAny<InstagramPostEdge>() {
    private val node by obj(JInstagramPost, InstagramPostEdge::node)

    override fun JsonNodeObject.deserializeOrThrow() = InstagramPostEdge(+node)
}
