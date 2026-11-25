plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shadowclone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shadowclone"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.opencv:opencv:4.14.0")

    // The UI redesign needs these three and nothing more: appcompat for the themed
    // activity, material for the bottom sheet and themed controls, recyclerview for
    // the lists and ItemTouchHelper's drag-to-reorder.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
