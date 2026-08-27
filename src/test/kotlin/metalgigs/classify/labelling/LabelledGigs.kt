package metalgigs.classify.labelling

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.bool
import com.ubertob.kondor.json.fromNdJsonToList
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.obj
import com.ubertob.kondor.json.str
import com.ubertob.kondor.json.toNdJson
import metalgigs.Genre
import metalgigs.Gig
import metalgigs.JGig
import java.io.File
import java.io.FileWriter

// Why a gig is kept whole and what the flag is for: docs/adr/0013-a-classifier-is-scored-against-gigs-a-person-labelled.md
internal data class LabelledGig(
    val gig: Gig,
    val genre: Genre,
    val why: String,
    val canBeDerivedPurelyFromText: Boolean = true,
)

internal class LabelledGigs(private val directory: File) {

    fun read(split: Split): List<LabelledGig> = file(split)
        .takeIf { it.exists() }
        ?.let { fromNdJsonToList(JLabelledGig)(it.readLines().asSequence()).orThrow() }
        ?: emptyList()

    fun all(): List<LabelledGig> = Split.entries.flatMap(::read)

    fun add(labelled: List<LabelledGig>) {
        labelled.groupBy { splitOf(it.gig) }.forEach { (split, rows) ->
            val target = file(split).apply { parentFile.mkdirs() }
            FileWriter(target, true).buffered().use { writer ->
                toNdJson(JLabelledGig)(rows).forEach { writer.appendLine(it) }
            }
        }
    }

    fun excluded(): List<ExcludedGig> = excludedFile
        .takeIf { it.exists() }
        ?.let { fromNdJsonToList(JExcludedGig)(it.readLines().asSequence()).orThrow() }
        ?: emptyList()

    fun exclude(rows: List<ExcludedGig>) {
        excludedFile.parentFile.mkdirs()
        FileWriter(excludedFile, true).buffered().use { writer ->
            toNdJson(JExcludedGig)(rows).forEach { writer.appendLine(it) }
        }
    }

    // labelled or left out - either way there is no reason to offer it again
    fun settled(): Set<metalgigs.GigId> = (all().map { it.gig.id } + excluded().map { it.gig.id }).toSet()

    private fun file(split: Split) = File(directory, "${split.name.lowercase()}.ndjson")
    private val excludedFile = File(directory, "excluded.ndjson")
}

internal enum class Split { Train, Test }

internal fun splitOf(gig: Gig): Split =
    if (Math.floorMod(gig.id.url.value.hashCode(), 10) < TEST_SHARE) Split.Test else Split.Train

private const val TEST_SHARE = 3

private object JLabelledGig : JAny<LabelledGig>() {
    private val gig by obj(JGig, LabelledGig::gig)
    private val genre by str(LabelledGig::genre)
    private val why by str(LabelledGig::why)
    private val canBeDerivedPurelyFromText by bool(LabelledGig::canBeDerivedPurelyFromText)

    override fun JsonNodeObject.deserializeOrThrow() =
        LabelledGig(+gig, +genre, +why, +canBeDerivedPurelyFromText)
}

// A gig with nothing to score, kept so it is not offered again: docs/adr/0013-a-classifier-is-scored-against-gigs-a-person-labelled.md
internal data class ExcludedGig(val gig: Gig, val why: String)

private object JExcludedGig : JAny<ExcludedGig>() {
    private val gig by obj(JGig, ExcludedGig::gig)
    private val why by str(ExcludedGig::why)

    override fun JsonNodeObject.deserializeOrThrow() = ExcludedGig(+gig, +why)
}
