package metalgigs

import java.time.Instant
import java.time.LocalDate

// Why the log looks like this: docs/adr/0001-the-log-is-append-only.md
sealed interface LogEntry {
    val recordedAt: Instant
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

// A gig its venue relisted at a new url - a support act swapped in, a festival's name settled on -
// where the url is built from the title and so changes with it. The gig is the same night in the same
// room, but a gig is identified by where it lives, so the log holds it twice and the page prints it
// twice; this says which of the two the venue has moved on from.
//
// Why recorded, not derived: docs/adr/0001-the-log-is-append-only.md
data class GigReplaced(
    val replaced: GigId,
    val by: GigId,
    override val recordedAt: Instant,
    override val seq: Long = UNSEQUENCED,
) : LogEntry {
    init {
        require(replaced != by) { "A gig can't replace itself: $replaced" }
    }

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
