pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }
}

rootProject.name = "Citrine"
include(":app")
include(":unused-code")
project(":unused-code").projectDir = file("tools/unused-code")
include(":architecture-tests")
project(":architecture-tests").projectDir = file("tools/architecture-tests")
include(":detekt-rules")
project(":detekt-rules").projectDir = file("tools/detekt-rules")
 