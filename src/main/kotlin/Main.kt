import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request

const val newsUrl = "https://www.cartandhorses.london/news-offers-events/"
const val gigsUrl = "https://www.newcrossinn.com/gigs/"
const val ourBlackHeartUrl = "https://www.ourblackheart.com/events"

fun fetchPage(client: HttpHandler, url: String): String =
    client(Request(GET, url)).bodyString()

fun main() {
    println(fetchPage(OkHttp(), newsUrl))
}
