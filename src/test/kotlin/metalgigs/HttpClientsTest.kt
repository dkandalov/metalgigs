package metalgigs

import dev.forkhandles.result4k.resultFrom
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.CLIENT_TIMEOUT
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.SERVICE_UNAVAILABLE
import org.http4k.core.then
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.Test

class HttpClientsTest {

    @Test
    fun `asks again after a timeout, which is what http4k calls a client that got no answer`() {
        val venue = answering(Response(CLIENT_TIMEOUT), Response(OK))

        expectThat(scrapeOf(venue).status).isEqualTo(OK)
        expectThat(venue.asked).isEqualTo(2)
    }

    @Test
    fun `hands back the last answer once the attempts are spent`() {
        val venue = answering(Response(SERVICE_UNAVAILABLE))

        expectThat(scrapeOf(venue).status).isEqualTo(SERVICE_UNAVAILABLE)
        expectThat(venue.asked).isEqualTo(3)
    }

    @Test
    fun `doesn't ask again for a page that is really gone`() {
        val venue = answering(Response(NOT_FOUND))

        expectThat(scrapeOf(venue).status).isEqualTo(NOT_FOUND)
        expectThat(venue.asked).isEqualTo(1)
    }

    @Test
    fun `gives up on a site answering slowly rather than not at all`() {
        dribbling().use { site ->
            val client = unredirectedHttpClient(Duration.ofMillis(200))

            expectThat(client(Request(GET, "http://localhost:${site.localPort}/gig")).status).isEqualTo(CLIENT_TIMEOUT)
        }
    }

    // A site that goes quiet is already caught by OkHttp's read timeout. One that keeps sending and
    // never finishes is the case only a call timeout ends, and the one Cart & Horses ran into.
    private fun dribbling(): ServerSocket = ServerSocket(0).also { site ->
        thread(isDaemon = true) {
            resultFrom { while (true) dribbleTo(site.accept()) }
        }
    }

    private fun dribbleTo(socket: Socket) = thread(isDaemon = true) {
        resultFrom {
            socket.use {
                val out = it.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                out.flush()
                while (true) {
                    out.write('.'.code)
                    out.flush()
                    Thread.sleep(20)
                }
            }
        }
    }

    private fun scrapeOf(venue: HttpHandler) =
        retrying(attempts = 3, backoff = Duration.ZERO).then(venue)(Request(GET, "https://example.com/gig"))

    private fun answering(vararg responses: Response) = AnsweringInTurn(responses.toList())

    // The last answer stands for every ask past it, so a test lists only what changes.
    private class AnsweringInTurn(private val responses: List<Response>) : HttpHandler {
        var asked = 0
            private set

        override fun invoke(request: Request) = responses[minOf(asked++, responses.lastIndex)]
    }
}
