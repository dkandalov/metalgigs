import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import org.http4k.server.Jetty
import org.http4k.server.asServer

val app: HttpHandler = routes(
    "/ping" bind GET to { Response(OK).body("pong") },
    "/hello/{name}" bind GET to { req: Request ->
        Response(OK).body("hello ${req.path("name")}")
    }
)

fun main() {
    app.asServer(Jetty(9000)).start()
    println("Server started on http://localhost:9000")
}
