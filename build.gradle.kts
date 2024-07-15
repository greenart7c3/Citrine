import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.5.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id ("com.android.library") version "8.5.1" apply false
    id("com.google.devtools.ksp") version "2.0.0-1.0.22" apply false
}

tasks.register<Copy>("installGitHook") {
    from(File(rootProject.rootDir, "git-hooks/pre-commit"))
    from(File(rootProject.rootDir, "git-hooks/pre-push"))
    into(File(rootProject.rootDir, ".git/hooks"))
    fileMode = 0b111101101
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
