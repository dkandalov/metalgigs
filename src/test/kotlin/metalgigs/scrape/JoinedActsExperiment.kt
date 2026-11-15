package metalgigs.scrape

import metalgigs.Ollama
import metalgigs.httpClient
import metalgigs.ollamaCallTimeout
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.model.SystemPrompt
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

// An experiment rather than a check on this project's behaviour: it asks the model the scrape uses
// about names already told apart by hand, and reports what it cut. It needs a running ollama and
// takes a few minutes, so the suite skips it unless JOINED_ACTS is set, the way DEV_FLYER gates the
// flyer experiment - and, as there, --rerun-tasks is what asks the model again.
//
// joined-acts.tsv is a name as a bill would write it, then the acts in it. 69 are one act whose name
// holds "&" or "and", off the Dice and AMG lineups of some forty London venues on 2026-11-15; 26 are
// two acts a venue's own bill joined that week.
// Why two readings have to agree: docs/adr/0015-a-lineup-is-the-acts-a-source-names-held-apart-from-the-title.md
class JoinedActsExperiment {

    @Test
    fun `tells two joined acts from one act whose name holds the word`() {
        assumeTrue(System.getenv("JOINED_ACTS") != null, "set JOINED_ACTS=1 to run this experiment")

        val model = JoinedActsReadByModel(Chat.Ollama(httpClient(ollamaCallTimeout), SystemPrompt.of(billReadingSystemPrompt)))
        val read = labelled.map { (joined, acts) -> Triple(joined, acts, model.apart(joined)) }

        val cutWrongly = read.filter { (_, acts, apart) -> apart.size > 1 && apart != acts.map { it.replace(noteInBrackets, "") } }
        val leftJoined = read.filter { (_, acts, apart) -> acts.size > 1 && apart.size == 1 }
        cutWrongly.forEach { (joined, _, apart) -> println("cut wrongly: $joined -> ${apart.joinToString(" | ")}") }
        leftJoined.forEach { (joined, _, _) -> println("left joined: $joined") }
        println(
            "${read.count { (_, acts, _) -> acts.size == 1 }} single acts, ${cutWrongly.size} cut wrongly; " +
                "${read.count { (_, acts, _) -> acts.size > 1 }} joined acts, ${leftJoined.size} left joined",
        )

        // A name left joined is still what the venue wrote, so only a wrong cut fails this.
        if (cutWrongly.isNotEmpty()) fail("${cutWrongly.size} name(s) cut wrongly - see the report above")
    }

    private val noteInBrackets = Regex("""\s*\([^)]*\)""")

    private val labelled: List<Pair<String, List<String>>> =
        File("src/test/resources/metalgigs/scrape/joined-acts.tsv").readLines()
            .filter { it.isNotBlank() }
            .map { line -> line.substringBefore('\t') to line.substringAfter('\t').split('|') }
}
