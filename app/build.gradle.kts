plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nocturne.visualizer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nocturne.visualizer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // key that lets the app upload its learning records (set as the NOCTURNE_UPLOAD_TOKEN secret on GitHub)
        buildConfigField("String", "UPLOAD_TOKEN", "\"" + (System.getenv("NOCTURNE_UPLOAD_TOKEN") ?: "") + "\"")
    }
    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            // signed with the debug key so the APK installs straight away; use your own key for the Play Store
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("mp3") }
}

