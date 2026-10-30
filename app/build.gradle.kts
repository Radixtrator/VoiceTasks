plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.voicetasks"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.voicetasks"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Phones only; the Gemma runtime adds ~30 MB per ABI.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The Gemma model is loaded from a file at runtime, never bundled.
    androidResources {
        noCompress += listOf("task", "litertlm")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.documentfile)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.play.services.wearable)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    // Real org.json for unit tests; android.jar only ships stubs.
    testImplementation(libs.org.json)
}
