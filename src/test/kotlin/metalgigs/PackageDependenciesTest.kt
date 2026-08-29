package metalgigs

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import kotlin.test.Test

class PackageDependenciesTest {

    // The steps meet through the Gig model in metalgigs and are wired together by Main, so none of
    // them has a reason to name another: a scraper that renders, or a renderer that goes back to a
    // venue's markup, is the pipeline collapsing into one step. Validation is here rather than
    // inside scrape because it judges a listing from the Gig model alone - a check that reached for
    // a scraper would be reading the page again rather than what came off it.
    @Test
    fun `scrape, validate, classify and render don't depend on each other`() {
        val scrape = Layer("scrape", "metalgigs.scrape..")
        val validate = Layer("validate", "metalgigs.validate..")
        val classify = Layer("classify", "metalgigs.classify..")
        val render = Layer("render", "metalgigs.render..")

        Konsist.scopeFromProduction().assertArchitecture {
            scrape.dependsOnNothing()
            validate.dependsOnNothing()
            classify.dependsOnNothing()
            render.dependsOnNothing()
        }
    }
}
