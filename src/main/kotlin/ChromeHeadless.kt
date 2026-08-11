import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit

// for venues whose pages are client-side rendered (the event listing only appears after JS runs),
// where plain HTTP GET + Jsoup sees nothing - this shells out to a real local Chrome install
// instead. Wrapped as an HttpHandler (same shape as OkHttp()) so it's a drop-in replacement: no
// GigsSource, fetchPage, or test-fixture-recording code needs to know or care that it's not a
// normal HTTP client underneath.

private val defaultChromeBinaryPaths = listOf(
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", // macOS
)

private fun resolveChromeBinary(override: String?): String =
    override
        ?: System.getenv("CHROME_BINARY")
        ?: defaultChromeBinaryPaths.firstOrNull { File(it).canExecute() }
        ?: error("Chrome binary not found. Set CHROME_BINARY to its path, or install Chrome at one of: $defaultChromeBinaryPaths")

fun ChromeHeadless(
    chromeBinary: String? = null,
    // Chrome's own internal virtual clock, giving the page's async JS time to finish before the DOM
    // is dumped - not wall-clock time, which for a heavy SPA runs longer and varies a lot (measured
    // 12-17s for the same page back to back), so processTimeout is a generous multiple of it rather
    // than a tight bound
    virtualTimeBudget: Duration = Duration.ofSeconds(15),
    processTimeout: Duration = Duration.ofMinutes(2),
): HttpHandler = { request ->
    val binary = resolveChromeBinary(chromeBinary)
    val url = request.uri.toString()

    val unsupportedHeaders = request.headers.map { (name, _) -> name }.filterNot { it.equals("User-Agent", ignoreCase = true) }
    check(unsupportedHeaders.isEmpty()) {
        "ChromeHeadless does not support header(s) $unsupportedHeaders (only User-Agent is supported, via --user-agent); called for $url"
    }
    val userAgentArgs = request.header("User-Agent")?.let { listOf("--user-agent=$it") } ?: emptyList()

    val stdout = Files.createTempFile("chrome-headless-stdout", ".html").toFile()
    val stderr = Files.createTempFile("chrome-headless-stderr", ".txt").toFile()
    try {
        val command = listOf(
            binary, "--headless=new", "--disable-gpu", "--dump-dom",
            "--virtual-time-budget=${virtualTimeBudget.toMillis()}",
        ) + userAgentArgs + url

        val process = try {
            ProcessBuilder(command).redirectOutput(stdout).redirectError(stderr).start()
        } catch (e: Exception) {
            error("Failed to launch Chrome at $binary: ${e.message}")
        }

        val finished = process.waitFor(processTimeout.toSeconds(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            error("Chrome headless timed out after $processTimeout fetching $url (virtual-time-budget=$virtualTimeBudget)")
        }
        check(process.exitValue() == 0) {
            "Chrome headless exited ${process.exitValue()} fetching $url: ${stderr.readText().take(2000)}"
        }

        Response(OK).body(stdout.readText())
    } finally {
        stdout.delete()
        stderr.delete()
    }
}
