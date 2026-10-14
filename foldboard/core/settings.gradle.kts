// The engine is a plain JVM library so it can be unit-tested without the Android SDK.
// The app consumes it as an included build (see ../settings.gradle.kts).
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "core"
