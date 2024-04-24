// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    id ("com.android.library") version "8.3.2" apply false
    id("com.google.devtools.ksp") version "1.9.23-1.0.20" apply false
}

tasks.register<Copy>("installGitHook") {
    from(File(rootProject.rootDir, "git-hooks/pre-commit"))
    from(File(rootProject.rootDir, "git-hooks/pre-push"))
    into(File(rootProject.rootDir, ".git/hooks"))
    fileMode = 0b111101101
}


tasks.getByPath(":app:preBuild").dependsOn(tasks.getByName("installGitHook"))

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            if (project.findProperty("composeCompilerReports") == "true") {
                freeCompilerArgs += listOf(
                    "-P",
                    "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=" +
                            project.buildDir.absolutePath + "/compose_compiler"
                )
            }
            if (project.findProperty("composeCompilerMetrics") == "true") {
                freeCompilerArgs += listOf(
                    "-P",
                    "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=" +
                            project.buildDir.absolutePath + "/compose_compiler"
                )
            }
        }
    }
}
