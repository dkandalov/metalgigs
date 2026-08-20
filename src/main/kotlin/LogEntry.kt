import java.time.Instant
import java.time.LocalDate

sealed interface LogEntry {
    val recordedAt: Instant

    // Says which of two entries was logged later, which recordedAt can't: a scrape or classification
    // run stamps every entry it appends with one Instant, so equal times are the norm, not the edge case.
    val seq: Long

    fun withSeq(seq: Long): LogEntry
}

data class GigObserved(
    val gig: Gig,
    override val recordedAt: Instant,
    override val seq: Long = UNSEQUENCED,
) : LogEntry {
    val id get() = gig.id
    override fun withSeq(seq: Long) = copy(seq = seq)
}

data class GigClassified(
    val id: GigId,
    override val recordedAt: Instant,
    val genre: Genre,
    val source: ClassificationSource,
    val llmModel: String? = null,
    val useVision: Boolean? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    override val seq: Long = UNSEQUENCED,
) : LogEntry {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

// logicalDate is the date the page was rendered as of - gigs before it are left off - which is
// today for a normal render but any date for a backdated one. Distinct from recordedAt, the wall
// clock: without it two renders of very different pages are told apart only by their gig count.
data class GigsRendered(
    val file: String,
    val gigCount: Int,
    val logicalDate: LocalDate,
    override val recordedAt: Instant,
    override val seq: Long = UNSEQUENCED,
) : LogEntry {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

enum class Genre { Metal, Other }

enum class ClassificationSource { LLM, User }

const val UNSEQUENCED = -1L
