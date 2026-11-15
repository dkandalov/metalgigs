package metalgigs.scrape

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.str
import dev.forkhandles.result4k.onFailure
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.ChatRequest
import org.http4k.ai.llm.chat.ChatResponseFormat
import org.http4k.ai.llm.model.Content
import org.http4k.ai.llm.model.Message
import org.http4k.ai.llm.model.ModelParams
import org.http4k.ai.model.ModelName
import org.http4k.format.MoshiArray
import org.http4k.format.MoshiNode
import org.http4k.format.MoshiObject
import org.http4k.format.MoshiString

// Why two readings have to agree: docs/adr/0015-a-lineup-is-the-acts-a-source-names-held-apart-from-the-title.md
internal class JoinedActsReadByModel(private val chat: Chat) : JoinedActs {
    override fun apart(joined: String): List<String> = alreadyRead.getOrPut(joined) {
        val separate = namedSeparately(joined)
        if (separate.size > 1 && countedAsSeveral(joined)) separate else listOf(joined)
    }

    // A run of nights carries one bill, and asking once is what gives every night of it one answer.
    private val alreadyRead = mutableMapOf<String, List<String>>()

    private fun namedSeparately(joined: String): List<String> {
        val named = JActsNamed.fromJson(reply(separatelyIfRecognised(joined), actsNamed)).orThrow().acts
        return actsAsWritten(joined, named.map { it.trim() }.filter { it.isNotEmpty() }) ?: listOf(joined)
    }

    private fun countedAsSeveral(joined: String) =
        JActsCounted.fromJson(reply(oneActOrSeveral(joined), actsCounted)).orThrow().answer == "two_or_more"

    private fun reply(prompt: String, heldTo: MoshiNode): String {
        val request = ChatRequest(Message.User(listOf(Content.Text(prompt))), ModelParams(model, responseFormat = ChatResponseFormat.Json(heldTo)))
        val response = chat(request).onFailure { error("Asking $model about a bill failed: $it") }
        return response.message.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
    }

    private val model = ModelName.of("gemma4:26b")

    private fun separatelyIfRecognised(joined: String) = """
        This line is printed under a concert's headline on a venue's website: «Plus special guests $joined»

        List the supporting acts it names, each one exactly as the line writes it, leaving out dates and notes in brackets. Names joined by "&" or "and" may be two separate acts or one act whose own name contains the word ("Echo & the Bunnymen"). Separate them ONLY if you recognise each side as an act that exists and performs on its own. If you do not recognise them, or are not sure, keep the joined name whole as a single entry - a wrongly separated name is far worse than one left whole.
    """.trimIndent()

    private fun oneActOrSeveral(joined: String) = """
        A concert listing names its support as: «$joined»

        Is that ONE act whose own name contains "&" or "and", or TWO OR MORE separate acts that each perform on their own? Answer "unsure" if you do not recognise the names.
    """.trimIndent()

    private val actsNamed = MoshiObject(
        "type" to MoshiString("object"),
        "properties" to MoshiObject("acts" to MoshiObject("type" to MoshiString("array"), "items" to MoshiObject("type" to MoshiString("string")))),
        "required" to MoshiArray(MoshiString("acts")),
    )

    private val actsCounted = MoshiObject(
        "type" to MoshiString("object"),
        "properties" to MoshiObject(
            "answer" to MoshiObject("type" to MoshiString("string"), "enum" to MoshiArray(MoshiString("one"), MoshiString("two_or_more"), MoshiString("unsure"))),
        ),
        "required" to MoshiArray(MoshiString("answer")),
    )
}

internal val billReadingSystemPrompt = "Answer in exactly the form asked for."

// The model's reading is only ever a way of cutting the bill: each act it names has to be there as
// written, in order, with nothing between two of them but the word that joined them or a note in
// brackets. A reply that reworded a name, or added one, cuts nothing.
internal fun actsAsWritten(joined: String, named: List<String>): List<String>? {
    var rest = joined
    val acts = named.map { name ->
        val at = rest.indexOf(name, ignoreCase = true)
        if (at < 0 || !rest.substring(0, at).matches(betweenActs)) return null
        rest.substring(at, at + name.length).also { rest = rest.substring(at + name.length) }
    }
    return acts.takeIf { rest.matches(betweenActs) }
}

private val betweenActs = Regex("""\s*(?:\([^)]*\))?\s*(?:&|and)?\s*""", RegexOption.IGNORE_CASE)

private data class ActsNamed(val acts: List<String>)

private object JActsNamed : JAny<ActsNamed>() {
    private val acts by array(ActsNamed::acts)
    override fun JsonNodeObject.deserializeOrThrow() = ActsNamed(+acts)
}

private data class ActsCounted(val answer: String)

private object JActsCounted : JAny<ActsCounted>() {
    private val answer by str(ActsCounted::answer)
    override fun JsonNodeObject.deserializeOrThrow() = ActsCounted(+answer)
}
