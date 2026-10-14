import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciVersion = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val keystorePath: String? = System.getenv("FOLDBOARD_KEYSTORE")

android {
    namespace = "app.foldboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.foldboard"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersion
        versionName = "0.1.$ciVersion"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("FOLDBOARD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FOLDBOARD_KEY_ALIAS")
                keyPassword = System.getenv("FOLDBOARD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release keystore (CI secrets), sign with the debug key so the APK installs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        // Don't let lint warnings block a CI build of a sideloaded APK.
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("app.foldboard:core:1.0")
}
