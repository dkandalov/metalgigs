plugins {
    kotlin("jvm") version "2.1.20"
    application
    // packages everything into one runnable jar: ./gradlew shadowJar -> build/libs/metalgigs-all.jar
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.http4k:http4k-bom:6.15.0.1"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-client-okhttp")
    implementation("org.http4k:http4k-tools-traffic-capture")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.ubertob.kondor:kondor-core:3.6.1")
    implementation("org.http4k:http4k-template-handlebars")
    implementation("org.http4k:http4k-ai-llm-anthropic")
    // handlebars drags in slf4j-api; without a provider on the classpath SLF4J prints a warning on
    // every run, and nothing here logs through it, so bind it to the no-op implementation
    runtimeOnly("org.slf4j:slf4j-nop:2.0.12")

    testImplementation(kotlin("test"))
    testImplementation("org.http4k:http4k-testing-approval")
    testImplementation("io.strikt:strikt-core:0.34.1")
    testImplementation("com.lemonappdev:konsist:0.17.3")
}

application {
    mainClass.set("metalgigs.MainKt")
    // main() sets this itself too, so a plain `java -jar` gets it as well - see the comment there
    applicationDefaultJvmArgs = listOf("-Dcom.sun.security.enableAIAcaIssuers=true")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("metalgigs.jar")
    // http4k and okhttp register implementations through META-INF/services; without merging, only
    // one jar's copy of a given service file would survive and those lookups would come up empty
    mergeServiceFiles()
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("com.sun.security.enableAIAcaIssuers", "true")
}

// The labelling loop is driven by a person rather than by the suite, and its ingest step writes to
// the committed evaluation set - so it is a main() in the test sources rather than a test, and this
// is what runs it. See .claude/skills/label-gigs.
tasks.register<JavaExec>("labelGigs") {
    group = "labelling"
    description = "Label gigs for the classifier evaluation set: --args=\"gigs-awaiting-labels 5\""
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("metalgigs.classify.labelling.LabellingKt")
}
