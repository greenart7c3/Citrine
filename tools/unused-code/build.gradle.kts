// Finds code nothing uses, from source, across every module: non-private
// declarations no source references, and catch parameters the catch block
// never reads. Run through the root `unusedCode` task.
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
}

dependencies {
    // Only its parser: files are read as syntax trees, never compiled.
    implementation(libs.kotlin.compiler.embeddable)

    testImplementation(libs.junit)
}
