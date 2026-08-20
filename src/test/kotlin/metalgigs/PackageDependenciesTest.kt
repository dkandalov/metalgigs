package metalgigs

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import kotlin.test.Test

class PackageDependenciesTest {

    // The three steps meet through the Gig model in metalgigs and are wired together by Main, so
    // none of them has a reason to name another: a scraper that renders, or a renderer that goes
    // back to a venue's markup, is the pipeline collapsing into one step.
    @Test
    fun `scrape, classify and render don't depend on each other`() {
        val scrape = Layer("scrape", "metalgigs.scrape..")
        val classify = Layer("classify", "metalgigs.classify..")
        val render = Layer("render", "metalgigs.render..")

        Konsist.scopeFromProduction().assertArchitecture {
            scrape.dependsOnNothing()
            classify.dependsOnNothing()
            render.dependsOnNothing()
        }
    }
}
