plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.smartglasses.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smartglasses.app"
        // Android 12+: runtime BLUETOOTH_SCAN/CONNECT, no location permission needed.
        minSdk = 31
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
        // LiteRT-LM ships arm64 native code; every phone this targets is arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The AI model is NOT in the APK: the user imports it from a file (see ai/ModelStore).
    // `offline` has no network permission at all; `withDownload` adds INTERNET only for
    // the optional in-app model download.
    flavorDimensions += "network"
    productFlavors {
        create("offline") {
            dimension = "network"
            isDefault = true
        }
        create("withDownload") {
            dimension = "network"
            versionNameSuffix = "-dl"
        }
    }

    buildTypes {
        release {
            // R8 drops the unused Material icons and other dead code (debug APK is ~110 MB, mostly dex).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // On-device AI: Gemma 4 E2B via LiteRT-LM, ML Kit OCR + image labelling (bundled models)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")

    // Nordic BLE central library + coroutine extensions
    implementation("no.nordicsemi.android:ble:2.11.0")
    implementation("no.nordicsemi.android:ble-ktx:2.11.0")

    testImplementation("junit:junit:4.13.2")
}
