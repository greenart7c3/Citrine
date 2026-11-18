import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kt.lint) apply false
}

tasks.register<Exec>("installGitHook") {
    // core.hooksPath instead of copying into .git/hooks: works in git worktrees,
    // where .git is a file and there is no per-worktree hooks dir.
    commandLine(
        "git",
        "config",
        "core.hooksPath",
        rootProject.layout.projectDirectory.dir("git-hooks").asFile.absolutePath,
    )
}


tasks.getByPath(":app:preBuild").dependsOn(tasks.getByName("installGitHook"))

subprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            val buildDirPath = layout.buildDirectory.get().asFile.absolutePath
            if (project.findProperty("composeCompilerReports") == "true") {
                freeCompilerArgs.addAll(
                    listOf(
                        "-P",
                        "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=$buildDirPath/compose_compiler"
                    )
                )
            }
            if (project.findProperty("composeCompilerMetrics") == "true") {
                freeCompilerArgs.addAll(
                    listOf(
                        "-P",
                        "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=$buildDirPath/compose_compiler"
                    )
                )
            }
        }
    }
}
