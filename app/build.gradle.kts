import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kt.lint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.jetbrainsComposeCompiler)
}

android {
    namespace = "com.greenart7c3.citrine"
    compileSdk = 37
    // AGP strips native libraries with this NDK while packaging, so it is an
    // APK input: pinned so release builds strip identically across AGP bumps.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.greenart7c3.citrine"
        minSdk = 26
        targetSdk = 36
        versionCode = 77
        versionName = "3.3.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        androidResources {
            localeFilters.addAll(
                setOf(
                    "en",
                    "de",
                    "es",
                    "fr",
                    "ru",
                    "zh",
                    "pt-rBR",
                    "in",
                    "ja",
                    "tr",
                    "vi-rVN",
                    "ko-rKR",
                    "it-rIT",
                    "th",
                ),
            )
        }

        lint {
            disable.add("MissingTranslation")
        }
    }

    if (System.getenv("SIGN_RELEASE") != null) {
        val keystorePropertiesFile = rootProject.file("keystore.properties")
        val keystoreProperties = Properties().apply {
            load(FileInputStream(keystorePropertiesFile))
        }

        signingConfigs {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            if (System.getenv("SIGN_RELEASE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            resValue("string", "app_name", "@string/app_name_release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    testOptions {
        // Crash-report assembly reads Build.*, Debug.getNativeHeap* and file sizes; returning
        // defaults instead of throwing keeps those paths unit-testable on the JVM.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            useLegacyPackaging = true
            // Already stripped by tools/arti/build.sh; ship it byte-identical to the
            // committed file so it can be checked against a rebuild of tools/arti.
            keepDebugSymbols += "**/libcitrine_arti.so"
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("x86", "x86_64", "arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}

// AGP generates BuildConfig as Java, which detekt cannot read (it parses only
// Kotlin sources), so its type resolution would see every BuildConfig reference
// as unresolved. Give each variant's detekt and baseline task the compiled
// BuildConfig (that variant's javac output) on its classpath. Adding to the
// classpath would drop the plugin's convention (the variant's libraries), so it
// is set again in full.
fun detektVariantClasspath(detektTaskName: String): List<Any>? {
    val variant = detektTaskName.removePrefix("detekt").removePrefix("Baseline")
    val kotlinCompile = "compile${variant}Kotlin"
    val javaCompile = "compile${variant}JavaWithJavac"
    if (kotlinCompile !in tasks.names || javaCompile !in tasks.names) return null
    val kotlin = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>(kotlinCompile)
    return listOf(
        kotlin.map { it.libraries },
        tasks.named<JavaCompile>(javaCompile).flatMap { it.destinationDirectory },
    )
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    detektVariantClasspath(name)?.let { classpath.setFrom(it) }
}
tasks.withType<dev.detekt.gradle.DetektCreateBaselineTask>().configureEach {
    detektVariantClasspath(name)?.let { classpath.setFrom(it) }
}

kover {
    reports {
        total {
            verify {
                rule {
                    // Just below the line coverage the app's unit tests reach (7.5%,
                    // debug). A ratchet: raise it when coverage improves, never lower
                    // it to make a change pass.
                    minBound(7)
                }
            }
        }
    }
}

dependencies {
    // Compose-specific rules (ModifierMissing, ViewModelForwarding, …) on top of
    // config/detekt/detekt.yml.
    detektPlugins(libs.detekt.compose.rules)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.androidx.core.ktx)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.websockets)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.quartz) {
        exclude("net.java.dev.jna")
    }
    implementation(libs.jna) {
        artifact { type = "aar" }
    }
    implementation(libs.androidx.room.runtime)
    implementation(libs.room.ktx)
    annotationProcessor(libs.room.compiler)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.storage)
    implementation(libs.material.icons.extended)
    implementation(libs.security.crypto.ktx)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.paging.common)
    implementation(libs.paging.compose)
    implementation(libs.paging.runtime)
    implementation(libs.room.paging)
    implementation(libs.negentropy)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
