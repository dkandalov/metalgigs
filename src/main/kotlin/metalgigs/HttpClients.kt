package metalgigs

import okhttp3.OkHttpClient
import org.http4k.client.OkHttp
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import java.time.Duration

internal fun httpClient(callTimeout: Duration = pageCallTimeout): HttpHandler =
    ClientFilters.FollowRedirects().then(unredirectedHttpClient(callTimeout))

internal fun unredirectedHttpClient(callTimeout: Duration = pageCallTimeout): HttpHandler =
    retrying().then(
        OkHttp(
            OkHttpClient.Builder()
                // http4k's own default client sets this and nothing else, so rebuilding it here has
                // to keep it or the Dice sources stop seeing the redirect they read a gig's url off.
                .followRedirects(false)
                .callTimeout(callTimeout)
                // OkHttp defaults this to 10 seconds, which a reply that arrives in one go runs into
                // long before the call timeout does: ollama sends nothing at all until the whole
                // extraction is written, and a minute of that silence is not a stalled socket.
                .readTimeout(callTimeout)
                .build()
        )
    )

// OkHttp's read timeout is per socket read and its call timeout - the only one bounding a request
// end to end - is off by default, so a site answering slowly rather than not at all held Cart &
// Horses at 32m53s on 2026-08-24, for a listing and 21 event pages that take 3s between them.
private val pageCallTimeout: Duration = Duration.ofSeconds(15)

// Anthropic takes seconds to judge a gig's genre and tens of them to read a poster, both of which
// the bound a venue's page is held to would cut short.
internal val llmCallTimeout: Duration = Duration.ofMinutes(2)

// A model running here answers a poster in under a minute once it's resident, but the first call of
// a run pays to read 17GB of weights off disk before it starts reading anything else.
internal val ollamaCallTimeout: Duration = Duration.ofMinutes(5)

// http4k maps every failure to get an answer at all onto a server error - a timeout to 504, a
// refused connection or an unknown host to 503 - so those are what asking again can fix, where a
// 404 is the page really being gone.
internal fun retrying(attempts: Int = 3, backoff: Duration = Duration.ofMillis(500)): Filter =
    Filter { next ->
        fun(request: Request): Response {
            repeat(attempts - 1) { attempt ->
                val response = next(request)
                if (!response.status.serverError) return response
                Thread.sleep(backoff.toMillis() * (attempt + 1))
            }
            return next(request)
        }
    }
