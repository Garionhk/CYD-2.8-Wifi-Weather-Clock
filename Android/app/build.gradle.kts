plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ca.garionhk.cydclock"
    compileSdk = 35

    defaultConfig {
        applicationId = "ca.garionhk.cydclock"
        // minSdk 26 is where java.time (ZoneId, ZonedDateTime) is native, which is
        // exactly what the IANA-timezone decision needs. Below 26 this would need
        // coreLibraryDesugaring.
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
    }

    buildTypes {
        release {
            // R8 on, because this is the build that runs for weeks on a wall.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Signed with the standard debug key so the APK can simply be
            // sideloaded. That is deliberate and has one consequence worth
            // knowing: a future build signed with a real release key will not
            // install over this one -- Android will ask you to uninstall first.
            // Creating a private signing key is the owner's call, not the
            // build's, so this stops short of doing it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // So the About section can state which build is actually installed.
        // Diagnosing a layout report by measuring pixel positions in a
        // screenshot is not a repeatable way to identify a version.
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    // The renderer, scenes, sun/moon math and units carry zero android.* imports
    // so the whole of that surface is testable as plain JVM unit tests.
    testImplementation(libs.junit)
}
