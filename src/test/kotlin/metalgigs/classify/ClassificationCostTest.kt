package metalgigs.classify

import metalgigs.*
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isNull
import java.time.Instant
import kotlin.test.Test

class ClassificationCostTest {

    private fun classified(
        model: String? = "claude-haiku-4-5-20251001",
        inputTokens: Int? = 1_000_000,
        outputTokens: Int? = 1_000_000,
        at: String = "2026-08-15T12:00:00Z",
    ) = GigClassified(
        id = GigId(VenueId("Test Venue"), GigUrl("https://example.com/gigs/a")),
        recordedAt = Instant.parse(at),
        genre = Genre.Metal,
        source = ClassificationSource.LLM,
        llmModel = model,
        useVision = model == "claude-sonnet-5",
        inputTokens = inputTokens,
        outputTokens = outputTokens,
    )

    @Test
    fun `prices the text model at its own rate`() {
        expectThat(classificationCost(classified())).isEqualTo(6.00)
    }

    // the vision model is on introductory pricing until the end of August 2026, so a run either
    // side of that reports what it actually cost rather than the other one's rate
    @Test
    fun `prices the vision model at the rate in force when it ran`() {
        expectThat(classificationCost(classified(model = "claude-sonnet-5", at = "2026-08-31T23:59:59Z")))
            .isEqualTo(12.00)
        expectThat(classificationCost(classified(model = "claude-sonnet-5", at = "2026-09-01T00:00:00Z")))
            .isEqualTo(18.00)
    }

    // a user's own override has no model and no tokens, and an entry written before tokens were
    // recorded has the model but not the counts - neither was free, so neither is priced at zero
    @Test
    fun `reports the two paths separately, and says what it could not price`() {
        val report = classificationCostReport(
            listOf(
                classified(inputTokens = 100_000, outputTokens = 3),
                classified(inputTokens = 100_000, outputTokens = 3),
                classified(model = "claude-sonnet-5", inputTokens = 1_000, outputTokens = 3),
                // a reply that arrived without usage - still a verdict, but nothing to price it by
                classified(inputTokens = null, outputTokens = null),
            ),
        )

        expectThat(report).containsExactly(
            "text      3 gig(s)  200000 in / 6 out  $0.2000",
            // the row two decimal places rounded away to $0.00
            "vision    1 gig(s)  1000 in / 3 out  $0.0020",
            "1 gig(s) reported no token usage, so are not counted above",
            "total  $0.2021",
        )
    }

    @Test
    fun `reports nothing for a run that classified nothing`() {
        expectThat(classificationCostReport(emptyList())).isEmpty()
    }

    @Test
    fun `prices nothing it has no numbers for`() {
        expectThat(classificationCost(classified(model = null))).isNull()
        expectThat(classificationCost(classified(inputTokens = null))).isNull()
        expectThat(classificationCost(classified(outputTokens = null))).isNull()
        expectThat(classificationCost(classified(model = "some-model-added-later"))).isNull()
    }
}
