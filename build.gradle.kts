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
    implementation("org.http4k:http4k-server-jetty")
    implementation("org.http4k:http4k-client-okhttp")
    implementation("org.http4k:http4k-tools-traffic-capture")

    testImplementation(kotlin("test"))
    testImplementation("org.http4k:http4k-testing-kotest")
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
