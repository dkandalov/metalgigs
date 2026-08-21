package metalgigs

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.JSealed
import com.ubertob.kondor.json.JStringRepresentable
import com.ubertob.kondor.json.bool
import com.ubertob.kondor.json.ObjectNodeConverter
import com.ubertob.kondor.json.datetime.str
import com.ubertob.kondor.json.fromNdJsonToList
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.num
import com.ubertob.kondor.json.obj
import com.ubertob.kondor.json.str
import com.ubertob.kondor.json.toNdJson
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDate

// Why every state is a projection: docs/adr/0001-the-log-is-append-only.md
class GigsLog(private val file: File) {
    var entries: List<LogEntry> = if (file.exists()) readLogEntries(file) else emptyList()
        private set

    fun append(newEntries: List<LogEntry>) {
        val sequenced = sequenced(newEntries)
        FileWriter(file, true).buffered().use { writer ->
            toNdJson(JLogEntry)(sequenced).forEach { writer.appendLine(it) }
        }
        entries = entries + sequenced
    }

    // A gig the log holds under a url its venue has moved on from isn't a gig any more, it's where
    // one used to live, so it leaves here rather than being published a second time beside the gig
    // that replaced it. A gig moved twice drops both of the urls it has left, each of them being
    // some replacement's `replaced`.
    fun currentGigs(): List<Gig> {
        val replaced = entries.filterIsInstance<GigReplaced>().map { it.replaced }.toSet()
        return entries.filterIsInstance<GigObserved>()
            .groupBy { it.id }
            .values
            .map { observations -> observations.maxBy { it.seq }.gig }
            .filterNot { it.id in replaced }
    }

    // scraped gigs not yet in the log, or that differ from their latest logged observation (e.g. a
    // title gaining "- SOLD OUT", a rescheduled date, or a changed description) - compares against
    // only the latest observation per gig, not the whole history, so a gig can be logged again after
    // reverting to a prior state
    fun newOrChangedGigs(scrapedGigs: List<Gig>): List<Gig> {
        val latestByGig = currentGigs().associateBy { it.id }
        return scrapedGigs.filter { gig -> latestByGig[gig.id] != gig }
    }

    // When each venue was last seen changing, which only approximates when it was last scraped.
    // Why that's enough: docs/adr/0001-the-log-is-append-only.md
    fun lastScrapedAt(): Map<VenueId, Instant> =
        entries.filterIsInstance<GigObserved>()
            .groupBy { it.id.venueId }
            .mapValues { (_, observations) -> observations.maxOf { it.recordedAt } }

    // every gig from one poster shares a "{sourceUrl}#..." url (see posterGigUrl), so one prefix check
    // covers the whole poster
    fun alreadyIngested(sourceUrl: String): Boolean =
        entries.filterIsInstance<GigObserved>().any { it.id.url.value.startsWith("$sourceUrl#") }

    // has the page already been rendered for this date? Matches on logicalDate rather than the newest
    // render's own timestamp, so a backdated render of some past date doesn't count as having done
    // today, and today's render still counts however long ago in the day it happened
    fun alreadyRenderedFor(date: LocalDate): Boolean =
        entries.filterIsInstance<GigsRendered>().any { it.logicalDate == date }

    fun classificationStatus(): Map<GigId, ClassificationStatus> {
        val own = entries.filterIsInstance<GigClassified>()
            .groupBy { it.id }
            .mapValues { (_, classifications) ->
                effectiveClassification(classifications)
                    ?.let { ClassificationStatus.Classified(it.genre) } ?: ClassificationStatus.Pending
            }
        return inheritedFrom(own.keys).mapNotNull { (gig, answers) -> own[answers]?.let { gig to it } }.toMap() + own
    }

    fun metalGigs(): List<Gig> {
        val statusByGig = classificationStatus()
        return currentGigs().filter { gig ->
            (statusByGig[gig.id] as? ClassificationStatus.Classified)?.genre == Genre.Metal
        }
    }

    fun alreadyClassified(): Set<GigId> =
        entries.filterIsInstance<GigClassified>().map { it.id }.toSet().let { it + inheritedFrom(it).keys }

    // What a forced reclassification leaves alone. A user's override wins over any LLM verdict
    // whenever it was recorded, so asking the classifier about such a gig again buys a paid call
    // whose answer effectiveClassification then discards.
    fun overriddenByUser(): Set<GigId> =
        entries.filterIsInstance<GigClassified>()
            .filter { it.source == ClassificationSource.User }
            .map { it.id }
            .toSet()
            .let { it + inheritedFrom(it).keys }

    // Why compaction keeps what it keeps: docs/adr/0001-the-log-is-append-only.md
    fun compact(): CompactedLog {
        val observations = entries.filterIsInstance<GigObserved>()
            .groupBy { it.id }
            .map { (_, observations) -> observations.maxBy { it.seq } }
        val classifications = entries.filterIsInstance<GigClassified>()
            .groupBy { it.id }
            .mapNotNull { (_, classifications) -> effectiveClassification(classifications) }
        val renders = entries.filterIsInstance<GigsRendered>()
        val replacements = entries.filterIsInstance<GigReplaced>()

        return CompactedLog(
            entries = (observations + classifications + renders + replacements).sortedBy { it.seq },
            observationsDropped = entries.count { it is GigObserved } - observations.size,
            classificationsDropped = entries.count { it is GigClassified } - classifications.size,
        )
    }

    // An entry that already carries a seq keeps it, so compacting - which appends a whole log's worth
    // of already-logged entries to an empty file - doesn't renumber what it keeps, and the gaps it
    // leaves say what was dropped. Anything unsequenced continues this log's numbering.
    private fun sequenced(newEntries: List<LogEntry>): List<LogEntry> {
        var previous = entries.lastOrNull()?.seq ?: UNSEQUENCED
        return newEntries.map { entry ->
            val seq = if (entry.seq == UNSEQUENCED) previous + 1 else entry.seq
            require(seq > previous) { "Entry seq $seq doesn't follow $previous, so the log wouldn't be in order: $entry" }
            previous = seq
            entry.withSeq(seq)
        }
    }

    // Which gig each gig that replaced another takes its answer from. Nothing is recorded against a
    // url a venue has only just written, so what the log holds about the gig at the old one is read
    // for it: a venue renaming a listing costs neither a paid call nor the verdict a user typed. The
    // chain is walked rather than followed one step, so a gig moved twice still reaches back to
    // where it was classified, and gigs that answer for themselves are left out of it entirely.
    private fun inheritedFrom(known: Set<GigId>): Map<GigId, GigId> {
        val replacedBy = entries.filterIsInstance<GigReplaced>().sortedBy { it.seq }.associate { it.by to it.replaced }
        return replacedBy.keys.filterNot { it in known }
            .mapNotNull { gig ->
                generateSequence(replacedBy[gig]) { replacedBy[it] }.firstOrNull { it in known }?.let { gig to it }
            }
            .toMap()
    }

    private fun effectiveClassification
(classifications: List<GigClassified>): GigClassified? {
        val latestBySource = classifications.groupBy { it.source }.mapValues { (_, cs) -> cs.maxBy { it.seq } }
        return latestBySource[ClassificationSource.User] ?: latestBySource[ClassificationSource.LLM]
    }
}

sealed interface ClassificationStatus {
    data class Classified(val genre: Genre) : ClassificationStatus

    data object Pending : ClassificationStatus {
        override fun toString() = "Pending (not yet classified)"
    }
}

data class CompactedLog(
    val entries: List<LogEntry>,
    val observationsDropped: Int,
    val classificationsDropped: Int,
)

private fun readLogEntries(file: File): List<LogEntry> =
    fromNdJsonToList(JLogEntry)(file.readLines().asSequence()).orThrow()

internal object JLogEntry : JSealed<LogEntry>() {
    override val subConverters: Map<String, ObjectNodeConverter<out LogEntry>> = mapOf(
        "observed" to JGigObserved,
        "classified" to JGigClassified,
        "rendered" to JGigsRendered,
        "replaced" to JGigReplaced,
    )

    override fun extractTypeName(obj: LogEntry): String = when (obj) {
        is GigObserved -> "observed"
        is GigClassified -> "classified"
        is GigsRendered -> "rendered"
        is GigReplaced -> "replaced"
    }
}

private object JGigObserved : JAny<GigObserved>() {
    private val seq by num(GigObserved::seq)
    private val gig by obj(JGig, GigObserved::gig)
    private val recordedAt by str(GigObserved::recordedAt)

    override fun JsonNodeObject.deserializeOrThrow() = GigObserved(+gig, +recordedAt, +seq)
}

private object JGigClassified : JAny<GigClassified>() {
    private val seq by num(GigClassified::seq)
    private val venue by str(JVenueId) { id.venueId }
    private val url by str(JGigUrl) { id.url }
    private val recordedAt by str(GigClassified::recordedAt)
    private val genre by str(GigClassified::genre)
    private val source by str(GigClassified::source)
    // optional, so entries written before these existed still read back (see GigClassified)
    private val llmModel by str(GigClassified::llmModel)
    private val useVision by bool(GigClassified::useVision)
    private val inputTokens by num(GigClassified::inputTokens)
    private val outputTokens by num(GigClassified::outputTokens)

    override fun JsonNodeObject.deserializeOrThrow() = GigClassified(
        id = GigId(+venue, +url),
        recordedAt = +recordedAt,
        genre = +genre,
        source = +source,
        llmModel = +llmModel,
        useVision = +useVision,
        inputTokens = +inputTokens,
        outputTokens = +outputTokens,
        seq = +seq,
    )
}

private object JGigReplaced : JAny<GigReplaced>() {
    private val seq by num(GigReplaced::seq)
    private val venue by str(JVenueId) { replaced.venueId }
    private val url by str(JGigUrl) { replaced.url }
    private val byVenue by str(JVenueId) { by.venueId }
    private val byUrl by str(JGigUrl) { by.url }
    private val recordedAt by str(GigReplaced::recordedAt)

    override fun JsonNodeObject.deserializeOrThrow() = GigReplaced(
        replaced = GigId(+venue, +url),
        by = GigId(+byVenue, +byUrl),
        recordedAt = +recordedAt,
        seq = +seq,
    )
}

private object JGigsRendered : JAny<GigsRendered>() {
    private val seq by num(GigsRendered::seq)
    private val file by str(GigsRendered::file)
    private val gigCount by num(GigsRendered::gigCount)
    private val logicalDate by str(GigsRendered::logicalDate)
    private val recordedAt by str(GigsRendered::recordedAt)

    override fun JsonNodeObject.deserializeOrThrow() = GigsRendered(+file, +gigCount, +logicalDate, +recordedAt, +seq)
}

private object JGig : JAny<Gig>() {
    private val title by str(JGigTitle) { title }
    private val venue by str(JVenueId) { id.venueId }
    private val date by str(JGigDate) { date }
    private val url by str(JGigUrl) { id.url }
    private val posterUrl by str(JPosterUrl) { posterUrl }
    private val description by str(JGigDescription) { description }

    override fun JsonNodeObject.deserializeOrThrow() = Gig(GigId(+venue, +url), +title, +date, +posterUrl, +description)
}

private object JVenueId : JStringRepresentable<VenueId>() {
    override val cons: (String) -> VenueId = ::VenueId
    override val render: (VenueId) -> String = VenueId::value
}

private object JGigUrl : JStringRepresentable<GigUrl>() {
    override val cons: (String) -> GigUrl = ::GigUrl
    override val render: (GigUrl) -> String = GigUrl::value
}

private object JGigTitle : JStringRepresentable<GigTitle>() {
    override val cons: (String) -> GigTitle = ::GigTitle
    override val render: (GigTitle) -> String = GigTitle::value
}

private object JGigDate : JStringRepresentable<GigDate>() {
    override val cons: (String) -> GigDate = GigDate::parse
    override val render: (GigDate) -> String = GigDate::toString
}

private object JPosterUrl : JStringRepresentable<PosterUrl>() {
    override val cons: (String) -> PosterUrl = ::PosterUrl
    override val render: (PosterUrl) -> String = PosterUrl::value
}

private object JGigDescription : JStringRepresentable<GigDescription>() {
    override val cons: (String) -> GigDescription = ::GigDescription
    override val render: (GigDescription) -> String = GigDescription::value
}
