plugins {
    kotlin("jvm") version "2.1.20"
    application
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

    testImplementation(kotlin("test"))
    testImplementation("org.http4k:http4k-testing-approval")
    testImplementation("io.strikt:strikt-core:0.34.1")
}

application {
    mainClass.set("MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
