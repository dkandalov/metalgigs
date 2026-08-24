package metalgigs

import com.ubertob.kondor.json.JAny
import com.ubertob.kondor.json.array
import com.ubertob.kondor.json.bool
import com.ubertob.kondor.json.jsonnode.JsonNodeObject
import com.ubertob.kondor.json.num
import com.ubertob.kondor.json.obj
import com.ubertob.kondor.json.str
import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import org.http4k.ai.llm.LLMError
import org.http4k.ai.llm.chat.Chat
import org.http4k.ai.llm.chat.ChatResponse
import org.http4k.ai.llm.model.Content
import org.http4k.ai.llm.model.Message
import org.http4k.ai.llm.model.Resource
import org.http4k.ai.model.ModelName
import org.http4k.ai.model.ResponseId
import org.http4k.ai.model.SystemPrompt
import org.http4k.ai.model.TokenUsage
import org.http4k.core.HttpHandler
import org.http4k.core.Method.POST
import org.http4k.core.Request

// A Chat answered by a model running under ollama on this machine, so a call costs nothing and the
// image never leaves the laptop. Only what a poster extraction asks of a Chat is implemented: a
// system prompt, one user message of text and images, and the reply read back as text.
internal fun Chat.Companion.Ollama(
    http: HttpHandler,
    systemPrompt: SystemPrompt,
    endpoint: String = "http://localhost:11434/api/chat",
): Chat = Chat { request ->
    val ollamaRequest = OllamaRequest(
        request.params.modelName.value,
        listOf(OllamaMessage("system", systemPrompt.value)) + request.messages.map(::ollamaMessage),
        // A gig's date read off a poster is a fact on it, so nothing is left to sampling; gemma4's
        // 12b and up emit an empty thinking block rather than dropping it, and what turning it off
        // buys is the seconds they would otherwise spend reasoning.
        options = OllamaOptions(temperature = 0.0, seed = 0, numCtx = 16384),
    )
    val response = http(Request(POST, endpoint).body(JOllamaRequest.toJson(ollamaRequest)))

    when {
        !response.status.successful -> Failure(LLMError.Http(response))
        else -> JOllamaResponse.fromJson(response.bodyString()).orThrow().let { reply ->
            Success(
                ChatResponse(
                    Message.Assistant(listOf(Content.Text(reply.message.content))),
                    // ollama stamps a reply rather than naming it, and the stamp is the only thing
                    // in the response that tells two of them apart
                    ChatResponse.Metadata(
                        ResponseId.of(reply.createdAt),
                        ModelName.of(reply.model),
                        TokenUsage(reply.promptEvalCount, reply.evalCount),
                    ),
                ),
            )
        }
    }
}

// ollama takes a message's images alongside its text rather than as content of their own, and takes
// them as bare base64 - the data: prefix an OpenAI-shaped API wants is what it would try to decode.
private fun ollamaMessage(message: Message): OllamaMessage = when (message) {
    is Message.System -> OllamaMessage("system", message.text)
    is Message.User -> OllamaMessage("user", message.contents.text(), message.contents.base64Images())
    is Message.Assistant -> OllamaMessage("assistant", message.contents.text())
    else -> error("${message::class.simpleName} is not a message this ollama chat sends")
}

private fun List<Content>.text() = filterIsInstance<Content.Text>().joinToString("\n") { it.text }

private fun List<Content>.base64Images() = filterIsInstance<Content.Image>().map {
    (it.image as? Resource.Binary ?: error("ollama takes an image's bytes, and ${it.image} only says where it lives"))
        .content.value
}

private data class OllamaMessage(val role: String, val content: String, val images: List<String> = emptyList())

private object JOllamaRequestMessage : JAny<OllamaMessage>() {
    private val role by str(OllamaMessage::role)
    private val content by str(OllamaMessage::content)
    private val images by array(OllamaMessage::images)

    override fun JsonNodeObject.deserializeOrThrow() = OllamaMessage(+role, +content, +images)
}

// the reply carries no images, so reading one back through the request's own converter would fail
// the parse on a field ollama had no reason to write
private object JOllamaReplyMessage : JAny<OllamaMessage>() {
    private val role by str(OllamaMessage::role)
    private val content by str(OllamaMessage::content)

    override fun JsonNodeObject.deserializeOrThrow() = OllamaMessage(+role, +content)
}

private data class OllamaOptions(val temperature: Double, val seed: Int, val numCtx: Int)

private object JOllamaOptions : JAny<OllamaOptions>() {
    private val temperature by num(OllamaOptions::temperature)
    private val seed by num(OllamaOptions::seed)
    private val num_ctx by num(OllamaOptions::numCtx)

    override fun JsonNodeObject.deserializeOrThrow() = OllamaOptions(+temperature, +seed, +num_ctx)
}

private data class OllamaRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false,
    val think: Boolean = false,
    val options: OllamaOptions,
)

private object JOllamaRequest : JAny<OllamaRequest>() {
    private val model by str(OllamaRequest::model)
    private val messages by array(JOllamaRequestMessage, OllamaRequest::messages)
    private val stream by bool(OllamaRequest::stream)
    private val think by bool(OllamaRequest::think)
    private val options by obj(JOllamaOptions, OllamaRequest::options)

    override fun JsonNodeObject.deserializeOrThrow() =
        OllamaRequest(+model, +messages, +stream, +think, +options)
}

private data class OllamaResponse(
    val model: String,
    val createdAt: String,
    val message: OllamaMessage,
    val promptEvalCount: Int?,
    val evalCount: Int?,
)

private object JOllamaResponse : JAny<OllamaResponse>() {
    private val model by str(OllamaResponse::model)
    private val created_at by str(OllamaResponse::createdAt)
    private val message by obj(JOllamaReplyMessage, OllamaResponse::message)
    private val prompt_eval_count by num(OllamaResponse::promptEvalCount)
    private val eval_count by num(OllamaResponse::evalCount)

    override fun JsonNodeObject.deserializeOrThrow() =
        OllamaResponse(+model, +created_at, +message, +prompt_eval_count, +eval_count)
}
