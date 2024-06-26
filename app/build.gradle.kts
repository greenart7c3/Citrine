plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

android {
    namespace = "com.greenart7c3.citrine"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.greenart7c3.citrine"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "0.2.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
        resourceConfigurations.addAll(
            setOf(
                "en",
                "ar",
                "bn-rBD",
                "cs",
                "cy-rGB",
                "da-rDK",
                "de",
                "el-rGR",
                "en-rGB",
                "eo",
                "es",
                "es-rES",
                "es-rMX",
                "es-rUS",
                "et-rEE",
                "fa",
                "fi-rFI",
                "fo-rFO",
                "fr",
                "fr-rCA",
                "gu-rIN",
                "hi-rIN",
                "hr-rHR",
                "hu",
                "in",
                "in-rID",
                "it-rIT",
                "iw-rIL",
                "ja",
                "kk-rKZ",
                "ko-rKR",
                "ks-rIN",
                "ku-rTR",
                "lt-rLT",
                "ne-rNP",
                "night",
                "nl",
                "nl-rBE",
                "pcm-rNG",
                "pl-rPL",
                "pt-rBR",
                "pt-rPT",
                "ru",
                "ru-rUA",
                "sa-rIN",
                "sl-rSI",
                "so-rSO",
                "sr-rSP",
                "ss-rZA",
                "sv-rSE",
                "sw-rKE",
                "sw-rTZ",
                "ta",
                "th",
                "tr",
                "uk",
                "ur-rIN",
                "uz-rUZ",
                "vi-rVN",
                "zh",
                "zh-rCN",
                "zh-rHK",
                "zh-rSG",
                "zh-rTW",
            ),
        )

        lint {
            disable.add("MissingTranslation")
        }
    }

    buildTypes {
        release {
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

dependencies {
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.websockets)
    implementation(libs.quartz) {
        exclude("net.java.dev.jna")
    }
    implementation(libs.ammolite) {
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
}
