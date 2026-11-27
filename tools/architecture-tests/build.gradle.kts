// Architecture rules the compiler cannot check: which package may import what,
// the Citrine.instance ratchet, and the shape of the service/database layers.
// Konsist reads the repository from the file system, so these run under
// `./gradlew test` (or `:architecture-tests:test`) without touching the app's
// test classpath. See docs/architecture.md for the target layering.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kt.lint)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnit()
    // The sources are read from disk, not from this module's inputs, so Gradle
    // would otherwise skip the tests whenever only the app changed.
    outputs.upToDateWhen { false }
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.junit)
}
