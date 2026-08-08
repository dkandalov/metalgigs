import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Status.Companion.OK
import kotlin.test.Test
import kotlin.test.assertEquals

class AppTest {
    @Test
    fun `ping returns pong`() {
        val response = app(Request(GET, "/ping"))
        assertEquals(OK, response.status)
        assertEquals("pong", response.bodyString())
    }

    @Test
    fun `hello returns greeting`() {
        val response = app(Request(GET, "/hello/world"))
        assertEquals(OK, response.status)
        assertEquals("hello world", response.bodyString())
    }
}
